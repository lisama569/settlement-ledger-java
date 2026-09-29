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
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportItem;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 进程重启持久性：文件型 H2（模拟平时的默认启动方式），第一个上下文写入并归档后关闭
 * （模拟进程退出，测试框架直接关闭上下文相当于非正常退出，没有任何优雅落盘钩子），
 * 第二个上下文（模拟重启）必须读到指令与历史（含已归档历史），继续推进、继续重建。
 */
class RestartPersistenceTest {

    private static final Logger log = LoggerFactory.getLogger(RestartPersistenceTest.class);

    @TempDir
    Path tempDir;

    /**
     * 全链路：受理/推进/归档（期间继续受理与推进）-> 关进程 -> 重启 ->
     * 查询、继续推进、重复重建、批量导出、幂等重提全部验证。
     */
    @Test
    void dataSurvivesRestart_andWorkContinues() {
        String dbFile = tempDir.resolve("settlement-restart").toAbsolutePath().toString();
        String key = "restart-" + UUID.randomUUID();
        String concurrentKey = "restart-concurrent-" + UUID.randomUUID();
        String runId = "run-restart-" + UUID.randomUUID();
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);

        Long instructionId;
        Long concurrentId;
        SettlementSnapshot beforeRestart;
        SnapshotExportPage exportBefore;

        // ---- 第一次“进程”：受理、推进、把历史拨旧，归档期间同时受理/推进新记录 ----
        try (ConfigurableApplicationContext ctx = startContext(dbFile)) {
            SettlementService settlementService = ctx.getBean(SettlementService.class);
            SnapshotService snapshotService = ctx.getBean(SnapshotService.class);
            ArchiveService archiveService = ctx.getBean(ArchiveService.class);
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            SubmitResult submitted = settlementService.submit(
                    new SubmitCommand(key, new BigDecimal("42.00"), "CNY", "pre", "post"));
            instructionId = submitted.instructionId();
            settlementService.advance(instructionId, submitted.version(), SettlementStatus.VALIDATED);

            // 把主指令历史拨到归档窗口之前并归档，验证重启后已归档历史仍参与重建
            jdbc.update("update audit_record set created_at = ? where instruction_id = ?",
                    Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), instructionId);

            // 归档批次登记后（模拟归档进行期间）同时受理并推进一条全新指令。
            // 它的 createdAt 在 cutoff 之后，既不能被本次归档搬走，重启后也不能丢/重。
            ArchiveResult archived = archiveService.archive(runId, cutoff);
            assertTrue(archived.completed());

            SubmitResult concurrent = settlementService.submit(
                    new SubmitCommand(concurrentKey, new BigDecimal("7.50"), "EUR", "cee", "dee"));
            concurrentId = concurrent.instructionId();
            settlementService.advance(concurrentId, concurrent.version(), SettlementStatus.VALIDATED);

            beforeRestart = snapshotService.rebuild(instructionId);
            exportBefore = exportAll(snapshotService);
            log.info("before restart: idempotencyKey={} instructionId={} status={} version={} archived={} exportItems={}",
                    key, instructionId, beforeRestart.status(), beforeRestart.version(),
                    archived.archivedCount(), exportBefore.items().size());
        }

        // ---- 第二次“进程”：同一数据库文件重新启动 ----
        try (ConfigurableApplicationContext ctx = startContext(dbFile)) {
            SettlementService settlementService = ctx.getBean(SettlementService.class);
            SnapshotService snapshotService = ctx.getBean(SnapshotService.class);
            ReplayService replayService = ctx.getBean(ReplayService.class);
            ArchiveService archiveService = ctx.getBean(ArchiveService.class);

            // 1) 快照逐字段一致（record 的 equals 覆盖全部字段，含 attributes/时间戳/金额）
            SettlementSnapshot afterRestart = snapshotService.rebuild(instructionId);
            SettlementStatus replayed = replayService.replayStatus(instructionId);
            log.info("after restart: idempotencyKey={} instructionId={} status={} version={} replayed={}",
                    key, instructionId, afterRestart.status(), afterRestart.version(), replayed);
            assertEquals(beforeRestart, afterRestart, "snapshot must survive restart field-by-field");
            assertEquals(afterRestart.status(), replayed, "replay must agree with live status after restart");

            // 2) 对同一段数据反复重建/导出，结果稳定可复现
            assertEquals(afterRestart, snapshotService.rebuild(instructionId),
                    "repeated rebuild must be identical");
            SnapshotExportPage exportAfter = exportAll(snapshotService);
            assertEquals(exportBefore, exportAfter, "batch export must be identical before/after restart");
            assertEquals(exportAfter, exportAll(snapshotService), "repeated export must be identical");
            assertTrue(exportAfter.items().stream().allMatch(SnapshotExportItem::ok),
                    "no export item may surface as a data issue after restart");

            // 3) 归档期间新受理/推进的记录：没丢、没被搬、没重复
            SettlementSnapshot concurrentSnapshot = snapshotService.rebuild(concurrentId);
            assertEquals(SettlementStatus.VALIDATED, concurrentSnapshot.status());
            assertEquals(concurrentKey, concurrentSnapshot.idempotencyKey());
            assertEquals(0, new java.math.BigDecimal("7.50").compareTo(concurrentSnapshot.amount()));

            // 4) 已归档历史重启后仍生效：同 runId 再归档直接回放，不重复搬
            ArchiveResult replay = archiveService.archive(runId, cutoff);
            assertTrue(replay.alreadyApplied(), "completed archive must remain a no-op after restart");

            // 5) 重启后继续推进：seq 接原有序号往下走，不回退不冲突
            AdvanceResult settled = settlementService.advance(
                    instructionId, afterRestart.version(), SettlementStatus.SETTLED);
            SettlementSnapshot finalSnapshot = snapshotService.rebuild(instructionId);
            assertEquals(SettlementStatus.SETTLED, finalSnapshot.status());
            assertEquals(settled.version(), finalSnapshot.version());
            assertEquals(SettlementStatus.SETTLED, replayService.replayStatus(instructionId));
            // 归档 2 条 + 继续推进 1 条，合并视图必须是连续 3 条
            assertEquals(3, countMergedHistory(ctx, instructionId),
                    "continued history must extend archived seq without gaps");

            // 6) 重启后幂等键仍生效：重复受理返回首次那条，不产生新指令
            SubmitResult dup = settlementService.submit(
                    new SubmitCommand(key, new BigDecimal("42.00"), "CNY", "pre", "post"));
            assertTrue(dup.duplicate());
            assertEquals(instructionId, dup.instructionId());
            SubmitResult dupConcurrent = settlementService.submit(
                    new SubmitCommand(concurrentKey, new BigDecimal("7.50"), "EUR", "cee", "dee"));
            assertTrue(dupConcurrent.duplicate());
            assertEquals(concurrentId, dupConcurrent.instructionId());
            log.info("post-restart idempotency holds: key={} -> instructionId={} duplicate={}",
                    key, dup.instructionId(), dup.duplicate());
        }
    }

    /** 反复用同一文件起第三个上下文：验证多次重启结果依旧稳定（可复现性）。 */
    @Test
    void multipleRestarts_remainStable() {
        String dbFile = tempDir.resolve("settlement-restart-multi").toAbsolutePath().toString();
        String key = "restart-multi-" + UUID.randomUUID();

        Long instructionId;
        SettlementSnapshot firstSnapshot;

        try (ConfigurableApplicationContext ctx = startContext(dbFile)) {
            SettlementService settlementService = ctx.getBean(SettlementService.class);
            SnapshotService snapshotService = ctx.getBean(SnapshotService.class);
            SubmitResult submitted = settlementService.submit(
                    new SubmitCommand(key, new BigDecimal("9.99"), "USD", "multi-a", "multi-b"));
            instructionId = submitted.instructionId();
            firstSnapshot = snapshotService.rebuild(instructionId);
        }

        for (int i = 0; i < 2; i++) {
            try (ConfigurableApplicationContext ctx = startContext(dbFile)) {
                SnapshotService snapshotService = ctx.getBean(SnapshotService.class);
                SettlementService settlementService = ctx.getBean(SettlementService.class);
                SettlementSnapshot snapshot = snapshotService.rebuild(instructionId);
                assertEquals(firstSnapshot, snapshot, "snapshot must stay identical across restart " + (i + 1));
                SubmitResult dup = settlementService.submit(
                        new SubmitCommand(key, new BigDecimal("9.99"), "USD", "multi-a", "multi-b"));
                assertTrue(dup.duplicate(), "idempotency must hold across restart " + (i + 1));
                assertEquals(instructionId, dup.instructionId());
            }
        }
    }

    private SnapshotExportPage exportAll(SnapshotService snapshotService) {
        int size = 200;
        List<SnapshotExportItem> all = new ArrayList<>();
        Long afterId = null;
        do {
            SnapshotExportPage page = snapshotService.exportRange(
                    new SnapshotExportRequest(null, null, afterId, size));
            all.addAll(page.items());
            afterId = page.nextCursor();
        } while (afterId != null);
        return new SnapshotExportPage(all, null);
    }

    private long countMergedHistory(ConfigurableApplicationContext ctx, Long instructionId) {
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        Long hot = jdbc.queryForObject(
                "select count(*) from audit_record where instruction_id = ?", Long.class, instructionId);
        Long archived = jdbc.queryForObject(
                "select count(*) from archived_audit_record where instruction_id = ?", Long.class, instructionId);
        return (hot == null ? 0 : hot) + (archived == null ? 0 : archived);
    }

    private ConfigurableApplicationContext startContext(String dbFile) {
        // 命令行参数源优先级高于 classpath 的测试内存库配置，确保这里真正用文件库模拟重启。
        // WRITE_DELAY=0 与默认启动配置一致：事务提交即落盘。
        return new SpringApplicationBuilder(Application.class).run(
                "--spring.datasource.url=jdbc:h2:file:" + dbFile + ";WRITE_DELAY=0;FILE_LOCK=NO",
                "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.main.web-application-type=none");
    }
}
