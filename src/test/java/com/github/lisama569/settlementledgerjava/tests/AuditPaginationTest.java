package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.dto.AuditPage;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AuditPaginationTest {

    private static final Logger log = LoggerFactory.getLogger(AuditPaginationTest.class);

    @Autowired
    SettlementService settlementService;

    @Test
    void keysetPagination_whileWriting_noGapsNoDuplicates() throws Exception {
        // 先铺一批数据
        for (int i = 0; i < 20; i++) {
            settlementService.submit(new SubmitCommand("page-seed-" + i + "-" + UUID.randomUUID(),
                    new BigDecimal("1.00"), "CNY", "p" + i, "q" + i));
        }

        // 后台持续写入
        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        Future<?> writing = writer.submit(() -> {
            int i = 0;
            while (!stop.get()) {
                settlementService.submit(new SubmitCommand("page-live-" + (i++) + "-" + UUID.randomUUID(),
                        new BigDecimal("2.00"), "CNY", "live", "live"));
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        // 小页遍历全量
        int pageSize = 7;
        List<Long> collected = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        while (true) {
            AuditPage page = settlementService.auditPage(cursor, pageSize);
            for (AuditRecord r : page.records()) {
                collected.add(r.getId());
            }
            pages++;
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
            assertTrue(pages < 100_000, "pagination must terminate");
        }
        stop.set(true);
        writing.get(10, TimeUnit.SECONDS);
        writer.shutdown();

        // 无重项
        Set<Long> unique = new HashSet<>(collected);
        log.info("pagination: pages={} collected={} distinct={} first={} last={}",
                pages, collected.size(), unique.size(),
                collected.isEmpty() ? null : collected.get(0),
                collected.isEmpty() ? null : collected.get(collected.size() - 1));
        assertEquals(collected.size(), unique.size(), "no duplicate items across pages");

        // 严格递增（键集游标性质）
        for (int i = 1; i < collected.size(); i++) {
            assertTrue(collected.get(i) > collected.get(i - 1), "ids must be strictly increasing");
        }

        // 无漏项：扫描结束后，凡 id <= 已见最大 id 的记录都必须已被收集
        AuditPage tail = settlementService.auditPage(null, 1);
        assertFalse(tail.records().isEmpty());
        long maxSeen = collected.get(collected.size() - 1);
        Long c = null;
        Set<Long> allUpToMax = new HashSet<>();
        while (true) {
            AuditPage page = settlementService.auditPage(c, 500);
            for (AuditRecord r : page.records()) {
                if (r.getId() <= maxSeen) {
                    allUpToMax.add(r.getId());
                }
            }
            if (page.nextCursor() == null) break;
            c = page.nextCursor();
        }
        assertEquals(allUpToMax, new HashSet<>(collected.stream().filter(id -> id <= maxSeen).toList()),
                "no missing items below the high-water mark");
    }

    @Test
    void emptyPage_hasNullCursor() {
        AuditPage page = settlementService.auditPage(Long.MAX_VALUE, 10);
        log.info("empty page: size={} nextCursor={}", page.records().size(), page.nextCursor());
        assertTrue(page.records().isEmpty());
        assertNull(page.nextCursor());
    }
}
