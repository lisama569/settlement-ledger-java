package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.Application;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotRebuildService;
import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 进程重启持久化：使用文件型 H2，第一个上下文受理/推进并归档部分历史后关闭；
 * 第二个上下文复用同一数据目录启动，验证指令、在线/归档历史都还在，
 * 且可继续推进、重建结果与重启前一致。
 */
class RestartPersistenceTest {

    private static final Logger log = LoggerFactory.getLogger(RestartPersistenceTest.class);

    @TempDir
    Path tempDir;

    @Test
    void historySurvivesRestart_andCanAdvanceAndRebuild() throws Exception {
        Path dbFile = tempDir.resolve("restart-db");
        String key = "restart-" + UUID.randomUUID();
        Long instructionId;
        String snapshotBeforeShutdown;

        // —— 第一个“进程”：受理 + 推进到 VALIDATED ——
        try (ConfigurableApplicationContext ctx = start(dbFile)) {
            SettlementService service = ctx.getBean(SettlementService.class);
            SnapshotRebuildService rebuild = ctx.getBean(SnapshotRebuildService.class);

            SubmitResult submitted = service.submit(
                    new SubmitCommand(key, new BigDecimal("99.00"), "CNY", "restart-a", "restart-b"));
            instructionId = submitted.instructionId();
            service.advance(instructionId, submitted.version(), SettlementStatus.VALIDATED);
            InstructionSnapshot snap = rebuild.rebuild(instructionId);
            snapshotBeforeShutdown = canonical(snap);
            assertEquals(SettlementStatus.VALIDATED, snap.status());
            assertEquals(1L, snap.version());
            log.info("before shutdown: idempotencyKey={} id={} status={} version={}",
                    key, instructionId, snap.status(), snap.version());
            assertTrue(Files.exists(Path.of(dbFile + ".mv.db")), "file-based store must be persisted");
        }

        // —— 模拟进程重启：第二个上下文复用同一数据文件 ——
        try (ConfigurableApplicationContext restarted = start(dbFile)) {
            SettlementService service = restarted.getBean(SettlementService.class);
            SnapshotRebuildService rebuild = restarted.getBean(SnapshotRebuildService.class);

            // 历史仍在，重建结果与重启前一致
            InstructionSnapshot afterRestart = rebuild.rebuild(instructionId);
            log.info("after restart: idempotencyKey={} id={} status={} version={} lastChangedAt={}",
                    key, instructionId, afterRestart.status(), afterRestart.version(),
                    afterRestart.lastChangedAt());
            assertEquals(snapshotBeforeShutdown, canonical(afterRestart),
                    "history must survive restart and rebuild identically");

            // 可继续推进（乐观版本延续），并产生新审计
            AdvanceResult settled = service.advance(instructionId, 1L, SettlementStatus.SETTLED);
            assertEquals(2L, settled.version());
            InstructionSnapshot finalSnap = rebuild.rebuild(instructionId);
            assertEquals(SettlementStatus.SETTLED, finalSnap.status());
            assertEquals(2L, finalSnap.version());
            log.info("after restart+advance: idempotencyKey={} status={} version={}",
                    key, finalSnap.status(), finalSnap.version());

            // 幂等受理在重启后仍然生效：同键重复提交返回首次结果
            SubmitResult duplicate = service.submit(
                    new SubmitCommand(key, new BigDecimal("99.00"), "CNY", "restart-a", "restart-b"));
            assertTrue(duplicate.duplicate());
            assertEquals(instructionId, duplicate.instructionId());
        }
    }

    private String canonical(InstructionSnapshot snap) {
        return snap.instructionId() + "|" + snap.idempotencyKey() + "|" + snap.amount().stripTrailingZeros()
                + "|" + snap.currency() + "|" + snap.payer() + "|" + snap.payee() + "|" + snap.channel()
                + "|" + snap.status() + "|" + snap.version() + "|" + snap.lastChangedAt()
                + "|" + snap.attributes();
    }

    private ConfigurableApplicationContext start(Path dbFile) {
        Properties props = new Properties();
        // AUTO_SERVER 关闭，确保同一时刻只有一个进程持有文件；DB_CLOSE_ON_EXIT=DELAY 避免 JVM 退出时强删。
        props.setProperty("spring.datasource.url",
                "jdbc:h2:file:" + dbFile.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE");
        props.setProperty("spring.datasource.driver-class-name", "org.h2.Driver");
        props.setProperty("spring.datasource.username", "sa");
        props.setProperty("spring.datasource.password", "");
        // 首次启动由实体建表；重启沿用，不能 create-drop。
        props.setProperty("spring.jpa.hibernate.ddl-auto", "update");
        props.setProperty("spring.jpa.open-in-view", "false");
        props.setProperty("spring.main.web-application-type", "none");
        props.setProperty("logging.level.org.hibernate", "WARN");

        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .initializers(applicationContext ->
                        applicationContext.getEnvironment().getPropertySources()
                                .addFirst(new PropertiesPropertySource("restart-db", props)))
                .run();
    }
}
