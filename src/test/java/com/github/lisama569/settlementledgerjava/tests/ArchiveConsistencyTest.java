package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.ArchiveService;
import com.github.lisama569.settlementledgerjava.service.ReplayService;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotService;
import com.github.lisama569.settlementledgerjava.service.dto.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 归档语义：
 * 1) 归档前后重建快照与重放状态完全一致；
 * 2) cutoff 之后新写入的记录不被归档，指令仍可继续推进；
 * 3) 同一 runId 重试/重复执行只生效一次（alreadyApplied）。
 */
@SpringBootTest
class ArchiveConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ArchiveConsistencyTest.class);

    @Autowired ArchiveService archiveService;
    @Autowired SnapshotService snapshotService;
    @Autowired ReplayService replayService;
    @Autowired SettlementService settlementService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void snapshotBeforeAndAfterArchive_isIdentical_andFutureWritesSurvive() throws Exception {
        String key = "arch-" + UUID.randomUUID();
        SubmitResult submitted = settlementService.submit(
                new SubmitCommand(key, new BigDecimal("100.00"), "CNY", "alice", "bob"));
        settlementService.advance(submitted.instructionId(), submitted.version(), SettlementStatus.VALIDATED);
        Long id = submitted.instructionId();

        // 把历史记录时间戳拨早，使其落入归档窗口
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        jdbcTemplate.update("update audit_record set created_at = ? where instruction_id = ?",
                Instant.parse("2020-01-01T00:00:00Z"), id);

        SettlementSnapshot before = snapshotService.rebuild(id);
        SettlementStatus replayBefore = replayService.replayStatus(id);
        long hotBefore = countHot(id);
        assertTrue(hotBefore >= 2);
        log.info("before archive: idempotencyKey={} status={} version={} hotRecords={}",
                key, before.status(), before.version(), hotBefore);

        String runId = "run-" + UUID.randomUUID();
        ArchiveResult first = archiveService.archive(runId, cutoff);
        assertTrue(first.completed());
        assertFalse(first.alreadyApplied());
        assertEquals(hotBefore, first.archivedCount(), "only backdated records fall in the archive window");

        // 记录确实搬出热表、进入归档表
        assertEquals(0L, countHot(id));
        assertEquals(hotBefore, countArchived(id));

        SettlementSnapshot after = snapshotService.rebuild(id);
        SettlementStatus replayAfter = replayService.replayStatus(id);
        log.info("after archive: idempotencyKey={} status={} version={} archivedRecords={}",
                key, after.status(), after.version(), countArchived(id));

        assertEquals(before, after, "rebuilt snapshot must be identical before/after archive");
        assertEquals(replayBefore, replayAfter, "replay must be identical before/after archive");

        // 归档后仍可继续推进：新 seq 不与归档历史冲突，快照继续一致
        AdvanceResult settled = settlementService.advance(id, after.version(), SettlementStatus.SETTLED);
        SettlementSnapshot live = snapshotService.rebuild(id);
        assertEquals(SettlementStatus.SETTLED, live.status());
        assertEquals(settled.version(), live.version());
        assertEquals(1L, countHot(id), "the new advance lands in the hot table");

        // 同 runId 再执行：只生效一次
        ArchiveResult retry = archiveService.archive(runId, cutoff);
        assertTrue(retry.completed());
        assertTrue(retry.alreadyApplied(), "same runId must not archive twice");
        assertEquals(first.archivedCount(), retry.archivedCount());
        log.info("archive idempotent: runId={} archivedCount={} alreadyApplied={}",
                runId, retry.archivedCount(), retry.alreadyApplied());

        // 推进之后的快照与最终状态一致
        assertEquals(SettlementStatus.SETTLED, snapshotService.rebuild(id).status());
    }

    private long countHot(Long id) {
        Long n = jdbcTemplate.queryForObject(
                "select count(*) from audit_record where instruction_id = ?", Long.class, id);
        return n == null ? 0L : n;
    }

    private long countArchived(Long id) {
        Long n = jdbcTemplate.queryForObject(
                "select count(*) from archived_audit_record where instruction_id = ?", Long.class, id);
        return n == null ? 0L : n;
    }
}
