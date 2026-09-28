package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.service.ArchiveFailureHolder;
import com.github.lisama569.settlementledgerjava.service.ArchiveServiceImpl;
import com.github.lisama569.settlementledgerjava.service.ArchiveService;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.dto.ArchiveResult;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.exception.ArchiveException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.github.lisama569.settlementledgerjava.repository.ArchiveRunRepository;
import com.github.lisama569.settlementledgerjava.repository.ArchivedAuditRecordRepository;
import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 归档失败重试：第二批事务失败回滚后，同一 runId 重试断点续跑，
 * 已提交的第一批不重复归档，未处理的记录不遗漏。
 */
@SpringBootTest(classes = {
        com.github.lisama569.settlementledgerjava.Application.class,
        ArchiveRetryTest.TestConfig.class})
class ArchiveRetryTest {

    private static final Logger log = LoggerFactory.getLogger(ArchiveRetryTest.class);
    private static final int BATCH = 200;

    @Autowired ArchiveService archiveService;
    @Autowired SettlementService settlementService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ArchiveFailureHolder failureHolder;

    @Configuration
    static class TestConfig {
        @Bean
        ArchiveFailureHolder archiveFailureHolder() {
            return new ArchiveFailureHolder();
        }

        @Bean
        ArchiveService archiveService(ArchiveRunRepository runs,
                                      ArchivedAuditRecordRepository archived,
                                      AuditRecordRepository hot,
                                      PlatformTransactionManager txManager,
                                      ArchiveFailureHolder holder) {
            return new ArchiveServiceImpl(runs, archived, hot, txManager, holder);
        }
    }

    @AfterEach
    void resetHook() {
        failureHolder.reset();
    }

    @Test
    void interruptedArchive_resumesWithSameRunId_noGapsNoDuplicates() {
        int total = 450;
        for (int i = 0; i < total; i++) {
            settlementService.submit(new SubmitCommand(
                    "retry-" + i + "-" + UUID.randomUUID(),
                    new BigDecimal("1.00"), "CNY", "a", "b"));
        }
        jdbcTemplate.update("update audit_record set created_at = ?",
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));

        Instant cutoff = Instant.parse("2021-01-01T00:00:00Z");
        String runId = "run-retry-" + UUID.randomUUID();

        // 第二批开始前注入故障：该批事务整体回滚，第一批（已提交）保留
        failureHolder.setHook(batchIndex -> {
            if (batchIndex == 1) {
                throw new IllegalStateException("simulated archive failure on batch 1");
            }
        });

        assertThrows(ArchiveException.class, () -> archiveService.archive(runId, cutoff));
        long afterFailure = jdbcTemplate.queryForObject(
                "select count(*) from archived_audit_record where run_id = ?", Long.class, runId);
        log.info("archived after failed attempt: {}", afterFailure);
        assertEquals(BATCH, afterFailure, "first committed batch survives the later rollback");

        // 解除故障，同一 runId 重试：从断点继续
        failureHolder.reset();
        ArchiveResult retry = archiveService.archive(runId, cutoff);
        assertTrue(retry.completed());
        assertFalse(retry.alreadyApplied());

        long archivedTotal = jdbcTemplate.queryForObject(
                "select count(*) from archived_audit_record where run_id = ?", Long.class, runId);
        long distinctSources = jdbcTemplate.queryForObject(
                "select count(distinct source_id) from archived_audit_record where run_id = ?",
                Long.class, runId);
        long ownHot = jdbcTemplate.queryForObject(
                "select count(*) from audit_record a join settlement_instruction i "
                        + "on a.instruction_id = i.id where i.idempotency_key like 'retry-%'",
                Long.class);
        log.info("retry result: archived={} distinctSources={} ownHot={}",
                archivedTotal, distinctSources, ownHot);
        assertEquals(total, archivedTotal, "every record archived exactly once");
        assertEquals(archivedTotal, distinctSources, "no record archived twice");
        assertEquals(0L, ownHot, "no eligible record left behind");

        // 再次执行同 runId：已完成批次直接回放，不再搬动
        ArchiveResult third = archiveService.archive(runId, cutoff);
        assertTrue(third.alreadyApplied());
        assertEquals(total, jdbcTemplate.queryForObject(
                "select count(*) from archived_audit_record where run_id = ?", Long.class, runId));
    }
}
