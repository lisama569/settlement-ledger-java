package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.audit.AuditHistoryEntry;
import com.github.lisama569.settlementledgerjava.audit.AuditHistoryReader;
import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;
import com.github.lisama569.settlementledgerjava.service.ArchiveService;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.ArchiveResult;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 归档进行期间持续受理/推进：新记录不丢不重、不被误归档；
 * 归档完成后每条指令的合并历史都连续完整。
 */
@SpringBootTest
class ArchiveConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ArchiveConcurrencyTest.class);

    @Autowired ArchiveService archiveService;
    @Autowired SettlementService settlementService;
    @Autowired AuditHistoryReader historyReader;
    @Autowired AuditRecordRepository auditRecordRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void archiveWhileWriting_losesNeitherOldNorNewRecords() throws Exception {
        // 先铺旧数据，并把时间戳拨到归档窗口之前
        List<Long> oldIds = new ArrayList<>();
        Instant oldTime = Instant.parse("2020-06-01T00:00:00Z");
        for (int i = 0; i < 10; i++) {
            SubmitResult s = settlementService.submit(new SubmitCommand(
                    "conc-arch-old-" + i + "-" + UUID.randomUUID(),
                    new BigDecimal("1.00"), "CNY", "a", "b"));
            oldIds.add(s.instructionId());
            jdbcTemplate.update("update audit_record set created_at = ? where instruction_id = ? and seq = 1",
                    oldTime, s.instructionId());
        }

        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);

        // 后台持续写入：受理 + 推进，时间戳都是 now（cutoff 之后）
        int writesPerThread = 25;
        ExecutorService writers = Executors.newFixedThreadPool(4);
        CountDownLatch stopGate = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int threadNo = t;
            futures.add(writers.submit(() -> {
                List<Long> created = new ArrayList<>();
                for (int i = 0; i < writesPerThread; i++) {
                    SubmitResult s = settlementService.submit(new SubmitCommand(
                            "conc-arch-new-" + threadNo + "-" + i + "-" + UUID.randomUUID(),
                            new BigDecimal("2.00"), "CNY", "c", "d"));
                    created.add(s.instructionId());
                    AdvanceResult v = settlementService.advance(
                            s.instructionId(), s.version(),
                            com.github.lisama569.settlementledgerjava.domain.SettlementStatus.VALIDATED);
                    settlementService.advance(s.instructionId(), v.version(),
                            com.github.lisama569.settlementledgerjava.domain.SettlementStatus.SETTLED);
                    if (i == 0) {
                        // 让归档与写入真正交叠
                        Thread.sleep(10);
                    }
                }
                return created;
            }));
        }

        String runId = "run-conc-" + UUID.randomUUID();
        ArchiveResult archiveResult = archiveService.archive(runId, cutoff);
        log.info("archive finished while writers running: runId={} archivedCount={}",
                runId, archiveResult.archivedCount());

        List<Long> newIds = new ArrayList<>();
        for (Future<List<Long>> f : futures) {
            newIds.addAll(f.get(60, TimeUnit.SECONDS));
        }
        writers.shutdown();

        // 1) 旧记录全部归档，热表不留、归档表不重
        for (Long id : oldIds) {
            assertEquals(0L, countHot(id), "old record must be moved out of hot table: " + id);
            assertEquals(1L, countArchived(id), "old record archived exactly once: " + id);
            List<AuditHistoryEntry> h = historyReader.historyOf(id);
            assertEquals(1, h.size());
            assertEquals(1L, h.get(0).seq());
        }

        // 2) 新记录全部留在热表，一条不丢
        for (Long id : newIds) {
            assertEquals(3L, countHot(id), "new instruction keeps SUBMIT+VALIDATE+SETTLE in hot table: " + id);
            assertEquals(0L, countArchived(id), "post-cutoff records must never be archived: " + id);
            List<AuditHistoryEntry> h = historyReader.historyOf(id);
            assertEquals(List.of(1L, 2L, 3L), h.stream().map(AuditHistoryEntry::seq).toList());
            assertEquals(com.github.lisama569.settlementledgerjava.domain.SettlementStatus.SETTLED,
                    h.get(h.size() - 1).toStatus());
        }

        // 3) 全局：所有审计记录恰好出现一次（热表或归档表），无重无漏
        long hotTotal = jdbcTemplate.queryForObject("select count(*) from audit_record", Long.class);
        long archivedTotal = jdbcTemplate.queryForObject("select count(*) from archived_audit_record", Long.class);
        long distinctSources = jdbcTemplate.queryForObject(
                "select count(distinct source_id) from archived_audit_record", Long.class);
        log.info("totals: newInstructions={} hotRecords={} archivedRecords={} distinctSources={}",
                newIds.size(), hotTotal, archivedTotal, distinctSources);
        assertEquals(archivedTotal, distinctSources, "no source record archived more than once");
        assertTrue(hotTotal >= newIds.size() * 3L);

        // 4) 归档后仍可推进一条旧指令（其历史已全部在归档表，seq 不能回退）
        AdvanceResult advanced = settlementService.advance(oldIds.get(0), 0L,
                com.github.lisama569.settlementledgerjava.domain.SettlementStatus.VALIDATED);
        assertEquals(1L, advanced.version());
        List<AuditHistoryEntry> oldAfter = historyReader.historyOf(oldIds.get(0));
        assertEquals(List.of(1L, 2L), oldAfter.stream().map(AuditHistoryEntry::seq).toList(),
                "advance after archive continues seq without collision");
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
