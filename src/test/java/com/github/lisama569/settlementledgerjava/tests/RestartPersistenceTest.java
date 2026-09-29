package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.Application;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.ArchiveService;
import com.github.lisama569.settlementledgerjava.service.ReplayService;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotService;
import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.ArchiveResult;
import com.github.lisama569.settlementledgerjava.service.dto.SettlementSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportPage;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportRequest;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 进程重启持久性：文件型 H2，第一个上下文写入并归档后关闭（模拟进程退出），
 * 第二个上下文（模拟重启）必须能读到指令与历史（含已归档历史），继续推进、继续重建。
 */
class RestartPersistenceTest {

    private static final Logger log = LoggerFactory.getLogger(RestartPersistenceTest.class);

    @TempDir
    Path tempDir;

    @Test
    void dataSurvivesRestart_andWorkContinues() {
        String dbFile = tempDir.resolve("settlement-restart").toAbsolutePath().toString();
        String key = "restart-" + UUID.randomUUID();
        String runId = "run-restart-" + UUID.randomUUID();
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);

        Long instructionId;
        SettlementSnapshot beforeRestart;

        // ---- 第一次“进程”：受理、推进、把历史拨旧并归档，然后关闭 ----
        try (ConfigurableApplicationContext ctx = startContext(dbFile)) {
            SettlementService settlementService = ctx.getBean(SettlementService.class);
            SnapshotService snapshotService = ctx.getBean(SnapshotService.class);
            ArchiveService archiveService = ctx.getBean(ArchiveService.class);

            SubmitResult submitted = settlementService.submit(
                    new SubmitCommand(key, new BigDecimal("42.00"), "CNY", "pre", "post"));
            instructionId = submitted.instructionId();
            settlementService.advance(instructionId, submitted.version(), SettlementStatus.VALIDATED);

            // 把历史拨到归档窗口之前并归档，验证重启后已归档历史仍参与重建
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            jdbc.update("update audit_record set created_at = ? where instruction_id = ?",
                    Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), instructionId);
            ArchiveResult archived = archiveService.archive(runId, cutoff);
            assertTrue(archived.completed());

            beforeRestart = snapshotService.rebuild(instructionId);
            log.info("before restart: idempotencyKey={} instructionId={} status={} version={} archived={}",
                    key, instructionId, beforeRestart.status(), beforeRestart.version(),
                    archived.archivedCount());
        }

        // ---- 第二次“进程”：同一数据库文件重新启动 ----
        try (ConfigurableApplicationContext ctx = startContext(dbFile)) {
            SettlementService settlementService = ctx.getBean(SettlementService.class);
            SnapshotService snapshotService = ctx.getBean(SnapshotService.class);
            ReplayService replayService = ctx.getBean(ReplayService.class);

            SettlementSnapshot afterRestart = snapshotService.rebuild(instructionId);
            SettlementStatus replayed = replayService.replayStatus(instructionId);
            log.info("after restart: idempotencyKey={} instructionId={} status={} version={} replayed={}",
                    key, instructionId, afterRestart.status(), afterRestart.version(), replayed);

            assertEquals(beforeRestart, afterRestart, "snapshot must survive restart unchanged");
            assertEquals(afterRestart.status(), replayed);

            // 重启后仍可继续推进（归档历史参与 seq 计算，不冲突）
            AdvanceResult settled = settlementService.advance(
                    instructionId, afterRestart.version(), SettlementStatus.SETTLED);
            SettlementSnapshot finalSnapshot = snapshotService.rebuild(instructionId);
            assertEquals(SettlementStatus.SETTLED, finalSnapshot.status());
            assertEquals(settled.version(), finalSnapshot.version());
            assertEquals(SettlementStatus.SETTLED, replayService.replayStatus(instructionId));

            // 重启后幂等键仍然生效：重复受理返回首次结果
            SubmitResult dup = settlementService.submit(
                    new SubmitCommand(key, new BigDecimal("42.00"), "CNY", "pre", "post"));
            assertTrue(dup.duplicate());
            assertEquals(instructionId, dup.instructionId());
            log.info("post-restart idempotency holds: idempotencyKey={} instructionId={} duplicate={}",
                    key, dup.instructionId(), dup.duplicate());
        }
    }

    @Test
    void exportAndArchiveState_surviveRestart_andSeqContinues() {
        String dbFile = tempDir.resolve("settlement-restart-export").toAbsolutePath().toString();
        String oldKey = "restart-old-" + UUID.randomUUID();
        String newKey = "restart-new-" + UUID.randomUUID();
        String runId = "run-restart-export-" + UUID.randomUUID();
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);

        Long oldId;
        Long newId;
        SnapshotExportPage exportBeforeRestart;

        // ---- 第一次“进程”：两条指令（旧的归档、新的不归档），归档进行期间也有新写入 ----
        try (ConfigurableApplicationContext ctx = startContext(dbFile)) {
            SettlementService settlementService = ctx.getBean(SettlementService.class);
            SnapshotService snapshotService = ctx.getBean(SnapshotService.class);
            ArchiveService archiveService = ctx.getBean(ArchiveService.class);
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            SubmitResult oldSub = settlementService.submit(
                    new SubmitCommand(oldKey, new BigDecimal("7.50"), "CNY", "old-pre", "old-post"));
            oldId = oldSub.instructionId();
            settlementService.advance(oldId, oldSub.version(), SettlementStatus.VALIDATED);
            // 旧指令的历史拨到 cutoff 之前
            jdbc.update("update audit_record set created_at = ? where instruction_id = ?",
                    Timestamp.from(Instant.parse("2020-03-01T00:00:00Z")), oldId);

            // 归档开始
            ArchiveResult archived = archiveService.archive(runId, cutoff);
            assertTrue(archived.completed());
            assertEquals(2L, archived.archivedCount());

            // 归档完成后仍有“新的受理 + 推进”，这批记录必须随重启保留
            SubmitResult newSub = settlementService.submit(
                    new SubmitCommand(newKey, new BigDecimal("8.25"), "USD", "new-pre", "new-post"));
            newId = newSub.instructionId();
            settlementService.advance(newId, newSub.version(), SettlementStatus.VALIDATED);

            exportBeforeRestart = dumpAll(snapshotService);
            assertEquals(2, exportBeforeRestart.items().size());
        }

        // ---- 第二次“进程”：归档状态与导出结果都必须原样恢复 ----
        try (ConfigurableApplicationContext ctx = startContext(dbFile)) {
            SettlementService settlementService = ctx.getBean(SettlementService.class);
            SnapshotService snapshotService = ctx.getBean(SnapshotService.class);
            ArchiveService archiveService = ctx.getBean(ArchiveService.class);
            ReplayService replayService = ctx.getBean(ReplayService.class);
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            // 同一批历史不会被再归档一次
            ArchiveResult again = archiveService.archive(runId, cutoff);
            assertTrue(again.alreadyApplied());
            assertEquals(2L, again.archivedCount());

            // 旧记录仍全部在归档表、热表为空；新记录仍在热表
            Long archivedRows = jdbc.queryForObject(
                    "select count(*) from archived_audit_record where instruction_id = ?", Long.class, oldId);
            Long oldHotRows = jdbc.queryForObject(
                    "select count(*) from audit_record where instruction_id = ?", Long.class, oldId);
            Long newHotRows = jdbc.queryForObject(
                    "select count(*) from audit_record where instruction_id = ?", Long.class, newId);
            assertEquals(2L, archivedRows);
            assertEquals(0L, oldHotRows);
            assertEquals(2L, newHotRows);

            // 合并重建旧指令：归档历史仍参与，快照与重放一致
            SettlementSnapshot oldSnap = snapshotService.rebuild(oldId);
            assertEquals(SettlementStatus.VALIDATED, oldSnap.status());
            assertEquals(SettlementStatus.VALIDATED, replayService.replayStatus(oldId));

            // 重启前后批量导出逐字段一致、可重复
            SnapshotExportPage firstAfter = dumpAll(snapshotService);
            SnapshotExportPage secondAfter = dumpAll(snapshotService);
            assertEquals(exportBeforeRestart, firstAfter,
                    "export must be identical across restart");
            assertEquals(firstAfter, secondAfter,
                    "repeated export must be reproducible");

            // 旧指令历史已在归档表，重启后继续推进的 seq 必须接 2 往下走，不回退、不冲突
            AdvanceResult settled = settlementService.advance(
                    oldId, oldSnap.version(), SettlementStatus.SETTLED);
            assertEquals(2L, settled.version());
            List<Long> hotSeqs = jdbc.queryForList(
                    "select seq from audit_record where instruction_id = ? order by seq asc",
                    Long.class, oldId);
            assertEquals(List.of(3L), hotSeqs,
                    "post-restart advance continues seq after archived history");
            assertEquals(SettlementStatus.SETTLED, replayService.replayStatus(oldId));
            assertEquals(SettlementStatus.SETTLED, snapshotService.rebuild(oldId).status());
        }
    }

    @Test
    void recordsWrittenDuringArchive_surviveRestart_withoutLossOrDuplication() throws Exception {
        String dbFile = tempDir.resolve("settlement-restart-duringarchive").toAbsolutePath().toString();
        String runId = "run-during-" + UUID.randomUUID();
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);

        List<Long> oldIds = new ArrayList<>();
        List<Long> newIds = new ArrayList<>();

        // ---- 进程 1：铺旧数据后，归档与并发受理/推进同时进行 ----
        try (ConfigurableApplicationContext ctx = startContext(dbFile)) {
            SettlementService settlementService = ctx.getBean(SettlementService.class);
            ArchiveService archiveService = ctx.getBean(ArchiveService.class);
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            // 旧记录拨到 cutoff 之前，归档应全部搬走
            Instant oldTime = Instant.parse("2020-06-01T00:00:00Z");
            for (int i = 0; i < 5; i++) {
                SubmitResult s = settlementService.submit(new SubmitCommand(
                        "during-old-" + i + "-" + UUID.randomUUID(),
                        new BigDecimal("1.00"), "CNY", "a", "b"));
                oldIds.add(s.instructionId());
                jdbc.update("update audit_record set created_at = ? where instruction_id = ? and seq = 1",
                        Timestamp.from(oldTime), s.instructionId());
            }

            // 后台写入线程：归档启动的同时持续受理 + 推进（时间戳均为 now，cutoff 之后）
            int writerThreads = 4;
            int perThread = 10;
            ExecutorService writers = Executors.newFixedThreadPool(writerThreads);
            CountDownLatch writersGate = new CountDownLatch(1);
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int t = 0; t < writerThreads; t++) {
                final int threadNo = t;
                futures.add(writers.submit(() -> {
                    writersGate.await();
                    List<Long> created = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        SubmitResult s = settlementService.submit(new SubmitCommand(
                                "during-new-" + threadNo + "-" + i + "-" + UUID.randomUUID(),
                                new BigDecimal("2.00"), "CNY", "c", "d"));
                        created.add(s.instructionId());
                        AdvanceResult v = settlementService.advance(
                                s.instructionId(), s.version(), SettlementStatus.VALIDATED);
                        settlementService.advance(s.instructionId(), v.version(), SettlementStatus.SETTLED);
                    }
                    return created;
                }));
            }

            writersGate.countDown();
            ArchiveResult archived = archiveService.archive(runId, cutoff);
            assertTrue(archived.completed());

            for (Future<List<Long>> f : futures) {
                newIds.addAll(f.get(60, TimeUnit.SECONDS));
            }
            writers.shutdown();
            assertEquals(writerThreads * perThread, newIds.size());
        }

        // ---- 进程 2：重启后，归档搬走的旧记录与并发新写入都不丢、不重 ----
        try (ConfigurableApplicationContext ctx = startContext(dbFile)) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            ArchiveService archiveService = ctx.getBean(ArchiveService.class);
            SnapshotService snapshotService = ctx.getBean(SnapshotService.class);

            // 归档批次重启后仍只生效一次
            ArchiveResult again = archiveService.archive(runId, cutoff);
            assertTrue(again.alreadyApplied());
            assertEquals(oldIds.size(), again.archivedCount());

            for (Long id : oldIds) {
                assertEquals(0L, count(jdbc, "audit_record", id),
                        "old record stays archived (hot empty) after restart: " + id);
                assertEquals(1L, count(jdbc, "archived_audit_record", id),
                        "old record archived exactly once after restart: " + id);
                SettlementSnapshot snap = snapshotService.rebuild(id);
                assertEquals(SettlementStatus.RECEIVED, snap.status());
            }

            for (Long id : newIds) {
                assertEquals(3L, count(jdbc, "audit_record", id),
                        "concurrent new records survive restart in hot table: " + id);
                assertEquals(0L, count(jdbc, "archived_audit_record", id),
                        "post-cutoff records never archived across restart: " + id);
                SettlementSnapshot snap = snapshotService.rebuild(id);
                assertEquals(SettlementStatus.SETTLED, snap.status());
            }

            // 全局：归档表里 source_id 无重复；无一条审计记录既在热表又在归档表
            Long distinctSources = jdbc.queryForObject(
                    "select count(distinct source_id) from archived_audit_record", Long.class);
            Long archivedTotal = jdbc.queryForObject(
                    "select count(*) from archived_audit_record", Long.class);
            assertEquals(archivedTotal, distinctSources, "no source archived more than once");
            Long overlap = jdbc.queryForObject(
                    "select count(*) from audit_record a where exists ("
                            + "select 1 from archived_audit_record x where x.source_id = a.id)", Long.class);
            assertEquals(0L, overlap, "no record exists in both hot and archive tables");
        }
    }

    private long count(JdbcTemplate jdbc, String table, Long instructionId) {
        Long n = jdbc.queryForObject(
                "select count(*) from " + table + " where instruction_id = ?", Long.class, instructionId);
        return n == null ? 0L : n;
    }

    /** 跨重启拉全量导出（分页逐页收集），用于逐字段对比。 */
    private SnapshotExportPage dumpAll(SnapshotService snapshotService) {
        // 数据量很小，一页足以装下；同时校验没有下一页游标
        SnapshotExportPage page = snapshotService.exportRange(
                new SnapshotExportRequest(null, null, null, 100));
        assertNull(page.nextCursor(), "fixture fits in one page");
        return page;
    }

    private ConfigurableApplicationContext startContext(String dbFile) {
        return startContext(dbFile, 0);
    }

    /**
     * 在文件型 H2 上启动一个独立 Spring 上下文（模拟一个进程）。
     * port &gt; 0 时同时启用 Web 容器，供需要真实 HTTP/独立 JVM 的用例使用。
     */
    private ConfigurableApplicationContext startContext(String dbFile, int port) {
        // 命令行参数源优先级高于 classpath 的 application.properties，避免被默认内存库覆盖
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Application.class);
        List<String> args = new ArrayList<>();
        args.add("--spring.datasource.url=jdbc:h2:file:" + dbFile);
        args.add("--spring.jpa.hibernate.ddl-auto=update");
        if (port > 0) {
            args.add("--server.port=" + port);
        } else {
            args.add("--spring.main.web-application-type=none");
        }
        return builder.run(args.toArray(new String[0]));
    }
}
