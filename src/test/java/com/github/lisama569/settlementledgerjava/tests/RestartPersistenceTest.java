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
import java.util.UUID;

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

    private ConfigurableApplicationContext startContext(String dbFile) {
        // 命令行参数源优先级高于 classpath 的 application.properties，避免被默认内存库覆盖
        return new SpringApplicationBuilder(Application.class).run(
                "--spring.datasource.url=jdbc:h2:file:" + dbFile,
                "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.main.web-application-type=none");
    }
}
