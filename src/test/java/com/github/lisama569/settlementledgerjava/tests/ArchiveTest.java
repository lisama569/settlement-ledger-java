package com.github.lisama569.settlementledgerjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.lisama569.settlementledgerjava.audit.AuditPayloadUpcaster;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.repository.ArchivedAuditRecordRepository;
import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;
import com.github.lisama569.settlementledgerjava.repository.SettlementInstructionRepository;
import com.github.lisama569.settlementledgerjava.service.ArchiveServiceImpl;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotRebuildService;
import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.ArchiveResult;
import com.github.lisama569.settlementledgerjava.service.dto.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import com.github.lisama569.settlementledgerjava.tests.support.LegacyAuditFixtures;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计归档：
 * - 归档前后重建快照完全一致；归档后仍可继续推进，重建仍正确；
 * - 归档与在线写入并发：不漏（归档范围内全部搬走）不重（同一 id 只存在一处）；
 * - 批次失败重试：失败批次回滚不留半成品，重试后全部记录只生效一次；
 * - 归档天然幂等：重复归档移动 0 条。
 */
@SpringBootTest
class ArchiveTest {

    private static final Logger log = LoggerFactory.getLogger(ArchiveTest.class);

    @Autowired
    SettlementService settlementService;
    @Autowired
    SnapshotRebuildService snapshotRebuildService;
    @Autowired
    ArchiveServiceImpl archiveService;
    @Autowired
    AuditRecordRepository auditRecordRepository;
    @Autowired
    ArchivedAuditRecordRepository archivedRepository;
    @Autowired
    SettlementInstructionRepository instructionRepository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactionTemplate;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void snapshotIdenticalBeforeAndAfterArchive_andCanStillAdvance() throws Exception {
        String key = "arch-mix-" + UUID.randomUUID();
        SubmitResult submitted = settlementService.submit(
                new SubmitCommand(key, new BigDecimal("42.00"), "CNY", "alice", "bob"));
        AdvanceResult validated = settlementService.advance(
                submitted.instructionId(), submitted.version(), SettlementStatus.VALIDATED);

        // 归档前快照（3 条审计：submitted + advanced 在此指令；只归档该指令涉及的前两条 id）
        InstructionSnapshot before = snapshotRebuildService.rebuild(submitted.instructionId());

        // 老 v1 指令一并归档，验证归档后老结构补齐结果不变
        String legacyKey = "arch-legacy-" + UUID.randomUUID();
        Long legacyId = LegacyAuditFixtures.seedSubmitted(jdbc, instructionRepository, legacyKey, 1,
                "{\"idempotencyKey\":\"" + legacyKey + "\",\"amount\":\"8.88\",\"currency\":\"USD\"}");
        InstructionSnapshot legacyBefore = snapshotRebuildService.rebuild(legacyId);

        // 归档全部现有记录（归档按确定 id 上界，本测试随后只校验自身指令范围内的数据）
        long highWater = jdbc.queryForObject("select coalesce(max(id),0) from audit_record", Long.class);
        ArchiveResult result = archiveService.archiveUpToId(highWater, 2);
        log.info("archived up to highWater: count={} highWater={}", result.archivedCount(), highWater);
        assertTrue(result.archivedCount() >= 3, "at least this test's audit records are archived");

        InstructionSnapshot after = snapshotRebuildService.rebuild(submitted.instructionId());
        InstructionSnapshot legacyAfter = snapshotRebuildService.rebuild(legacyId);
        assertEquals(mapper.writeValueAsString(before), mapper.writeValueAsString(after),
                "snapshot must be identical after archiving");
        assertEquals(mapper.writeValueAsString(legacyBefore), mapper.writeValueAsString(legacyAfter),
                "legacy snapshot must be identical after archiving");

        // 归档后仍可继续推进：新审计进在线表，与已归档历史合并后链条连续
        AdvanceResult settled = settlementService.advance(
                submitted.instructionId(), validated.version(), SettlementStatus.SETTLED);
        InstructionSnapshot finalSnap = snapshotRebuildService.rebuild(submitted.instructionId());
        assertEquals(SettlementStatus.SETTLED, finalSnap.status());
        assertEquals(settled.version(), finalSnap.version());
        assertEquals(2L, finalSnap.version());

        // 同一 id 只存在一处：本测试指令（2 条历史 + 1 条新推进）在两表中合计 3 条，无重复
        Long liveForIns = jdbc.queryForObject(
                "select count(*) from audit_record where instruction_id = ?", Long.class, submitted.instructionId());
        Long archivedForIns = jdbc.queryForObject(
                "select count(*) from archived_audit_record where instruction_id = ?", Long.class, submitted.instructionId());
        assertEquals(1L, liveForIns, "the new advance stays live");
        assertEquals(2L, archivedForIns, "the two earlier events are archived");
        Long legacyLive = jdbc.queryForObject(
                "select count(*) from audit_record where instruction_id = ?", Long.class, legacyId);
        Long legacyArchived = jdbc.queryForObject(
                "select count(*) from archived_audit_record where instruction_id = ?", Long.class, legacyId);
        assertEquals(0L, legacyLive);
        assertEquals(1L, legacyArchived);

        // 重复归档：边界内已无候选可搬，幂等
        ArchiveResult again = archiveService.archiveUpToId(highWater, 2);
        assertEquals(0L, again.archivedCount(), "re-archiving the same range moves nothing");
    }

    @Test
    void archiveConcurrentWithWrites_noLossNoDuplicate() throws Exception {
        // 先铺底
        for (int i = 0; i < 10; i++) {
            settlementService.submit(new SubmitCommand("arch-seed-" + i + "-" + UUID.randomUUID(),
                    new BigDecimal("1.00"), "CNY", "a", "b"));
        }
        final long boundary = jdbc.queryForObject("select coalesce(max(id),0) from audit_record", Long.class);
        long expectedInRange = jdbc.queryForObject(
                "select count(*) from audit_record where id <= ?", Long.class, boundary);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch ready = new CountDownLatch(threads + 1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger written = new AtomicInteger();

        // 多个写入线程在归档进行期间持续受理/推进
        for (int t = 0; t < threads; t++) {
            final int tt = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 20; i++) {
                    SubmitResult r = settlementService.submit(new SubmitCommand(
                            "arch-live-" + tt + "-" + i + "-" + UUID.randomUUID(),
                            new BigDecimal("2.00"), "CNY", "live", "live"));
                    if (i % 2 == 0) {
                        settlementService.advance(r.instructionId(), r.version(), SettlementStatus.VALIDATED);
                    }
                    written.incrementAndGet();
                }
            });
        }

        // 归档线程只搬迁确定边界内的记录，绝不混入边界外新数据
        FutureWithLatch archiveDone = new FutureWithLatch();
        pool.submit(() -> {
            ready.countDown();
            try {
                go.await();
                ArchiveResult r = archiveService.archiveUpToId(boundary, 3);
                archiveDone.result = r;
                archiveDone.latch.countDown();
            } catch (Throwable e) {
                archiveDone.error = e;
                archiveDone.latch.countDown();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        archiveDone.latch.await(60, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        assertNull(archiveDone.error, "archive must not fail: " + archiveDone.error);
        log.info("concurrent archive: moved={} newInstructions={}", archiveDone.result.archivedCount(), written.get());

        // 不漏：边界内全部进入归档表
        long archivedInRange = jdbc.queryForObject(
                "select count(*) from archived_audit_record where id <= ?", Long.class, boundary);
        assertEquals(expectedInRange, archivedInRange, "all in-range records archived, none lost");

        // 不重：在线/归档两表 id 不相交
        Long overlap = jdbc.queryForObject(
                "select count(*) from audit_record a join archived_audit_record b on a.id = b.id", Long.class);
        assertEquals(0L, overlap, "no record exists in both tables");

        // 不重：归档表主键唯一（重复搬迁不可能成功两次），计数与 id 数一致
        Long archivedRows = jdbc.queryForObject("select count(*) from archived_audit_record", Long.class);
        Long archivedDistinctIds = jdbc.queryForObject(
                "select count(distinct id) from archived_audit_record", Long.class);
        assertEquals(archivedRows, archivedDistinctIds);

        // 边界外的新写入全部留在在线表，未被误归档
        Long liveOutOfRange = jdbc.queryForObject(
                "select count(*) from audit_record where id > ?", Long.class, boundary);
        assertTrue(liveOutOfRange >= written.get(), "concurrent writes remain live and are not archived");
    }

    @Test
    void failedBatchRollsBack_andRetryTakesEffectExactlyOnce() {
        // 造 7 条记录，batchSize=3：批次为 3 / 3 / 1。只按本测试写入的 id 范围统计，
        // 避免共享内存库中其他测试数据干扰。
        long startId = jdbc.queryForObject("select coalesce(max(id),0) from audit_record", Long.class);
        for (int i = 0; i < 7; i++) {
            settlementService.submit(new SubmitCommand("arch-retry-" + i + "-" + UUID.randomUUID(),
                    new BigDecimal("1.00"), "CNY", "a", "b"));
        }
        long endId = jdbc.queryForObject("select coalesce(max(id),0) from audit_record", Long.class);
        assertEquals(startId + 7, endId);

        // 先把本窗口之前其它测试遗留的在线记录全部归档，使在线表在 (startId, endId] 内恰为这 7 条，
        // 保证按 id 升序分批时批次严格为 3 / 3 / 1。
        archiveService.archiveUpToId(startId, 1000);
        assertEquals(7L, countLive(startId, endId));

        // 第 2 批失败：第 1 批（3 条）已提交保留；第 2 批整批回滚，不留半成品。
        FlakyArchiveService flaky = new FlakyArchiveService(
                auditRecordRepository, archivedRepository, transactionTemplate, 2);
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> flaky.archiveUpToId(endId, 3));
        log.info("expected archive batch failure: {}", failure.getMessage());

        long archivedAfterFail = countArchived(startId, endId);
        long liveAfterFail = countLive(startId, endId);
        assertEquals(3L, archivedAfterFail, "first batch stays committed");
        assertEquals(4L, liveAfterFail, "failed batch rolls back completely, no half-moved data");

        // 重试（恢复正常的归档服务）：剩余 4 条全部搬走，归档与在线总数守恒且无重叠
        ArchiveResult retried = archiveService.archiveUpToId(endId, 3);
        assertEquals(4L, retried.archivedCount(), "retry moves only the remaining records once");
        assertEquals(0L, countLive(startId, endId));
        assertEquals(7L, countArchived(startId, endId), "retry completes the archive with no duplicates");

        // 再试一次：0 条，归档只生效一次
        assertEquals(0L, archiveService.archiveUpToId(endId, 3).archivedCount());
    }

    private long countLive(long startIdExclusive, long endIdInclusive) {
        return jdbc.queryForObject("select count(*) from audit_record where id > ? and id <= ?",
                Long.class, startIdExclusive, endIdInclusive);
    }

    private long countArchived(long startIdExclusive, long endIdInclusive) {
        return jdbc.queryForObject("select count(*) from archived_audit_record where id > ? and id <= ?",
                Long.class, startIdExclusive, endIdInclusive);
    }

    @Test
    void archiveByTimeCutoff_onlyMovesOlderRecords() throws Exception {
        String oldKey = "arch-time-old-" + UUID.randomUUID();
        SubmitResult oldOne = settlementService.submit(
                new SubmitCommand(oldKey, new BigDecimal("1.00"), "CNY", "a", "b"));
        Thread.sleep(15);
        Instant cutoff = Instant.now();
        Thread.sleep(15);
        String newKey = "arch-time-new-" + UUID.randomUUID();
        SubmitResult newOne = settlementService.submit(
                new SubmitCommand(newKey, new BigDecimal("2.00"), "CNY", "c", "d"));

        ArchiveResult result = archiveService.archiveBefore(cutoff, 10);
        log.info("time archive: moved={} cutoff={}", result.archivedCount(), cutoff);

        // 老记录已归档，新记录仍在线，两者重建均正确
        Long oldLive = jdbc.queryForObject(
                "select count(*) from audit_record where instruction_id = ?", Long.class, oldOne.instructionId());
        Long newLive = jdbc.queryForObject(
                "select count(*) from audit_record where instruction_id = ?", Long.class, newOne.instructionId());
        assertEquals(0L, oldLive, "older instruction archived");
        assertEquals(1L, newLive, "newer instruction stays live");
        assertEquals(SettlementStatus.RECEIVED,
                snapshotRebuildService.rebuild(oldOne.instructionId()).status());
        assertEquals(SettlementStatus.RECEIVED,
                snapshotRebuildService.rebuild(newOne.instructionId()).status());
    }

    /** 在第 failOnBatch 批提交前抛异常，模拟归档进行中批失败；成功批次仍按事务提交。 */
    static class FlakyArchiveService extends ArchiveServiceImpl {
        private final AtomicInteger batchSeq = new AtomicInteger();
        private final int failOnBatch;

        FlakyArchiveService(AuditRecordRepository live, ArchivedAuditRecordRepository archived,
                            TransactionTemplate transactionTemplate, int failOnBatch) {
            super(live, archived, transactionTemplate);
            this.failOnBatch = failOnBatch;
        }

        @Override
        protected void beforeBatchCommit(long maxIdInclusive) {
            if (batchSeq.incrementAndGet() == failOnBatch) {
                throw new IllegalStateException("simulated archive batch failure at batch " + failOnBatch);
            }
        }
    }

    static class FutureWithLatch {
        volatile ArchiveResult result;
        volatile Throwable error;
        final CountDownLatch latch = new CountDownLatch(1);
    }
}
