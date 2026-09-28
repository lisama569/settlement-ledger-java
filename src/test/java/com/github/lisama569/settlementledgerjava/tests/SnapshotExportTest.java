package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotService;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportItem;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportPage;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 按范围批量重建导出：空范围/刚好一页/跨页边界、新旧结构混合、
 * 重复导出完全一致、数据问题以错误项可见（不静默跳过、不当正常快照）。
 */
@SpringBootTest
class SnapshotExportTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotExportTest.class);

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SettlementService settlementService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private SubmitResult seed(String prefix, String amount, String currency) {
        return settlementService.submit(new SubmitCommand(
                prefix + "-" + UUID.randomUUID(), new BigDecimal(amount), currency, "p", "q"));
    }

    private List<SnapshotExportItem> drain(Long afterId, Instant from, Instant to, int size) {
        List<SnapshotExportItem> all = new ArrayList<>();
        Long cursor = afterId;
        int pages = 0;
        while (true) {
            SnapshotExportPage page = snapshotService.exportRange(
                    new SnapshotExportRequest(from, to, cursor, size));
            all.addAll(page.items());
            pages++;
            if (page.nextCursor() == null) {
                return all;
            }
            cursor = page.nextCursor();
            assertTrue(pages < 10_000, "export must terminate");
        }
    }

    @Test
    void emptyRange_returnsEmptyAndNullCursor() {
        SnapshotExportPage page = snapshotService.exportRange(
                new SnapshotExportRequest(null, Instant.parse("2000-01-01T00:00:00Z"), null, 10));
        log.info("empty range: items={} nextCursor={}", page.items().size(), page.nextCursor());
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void exactPage_thenCrossPage_boundariesAndConsistency() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(seed("export", "3.00", "CNY").instructionId());
        }
        Long firstId = ids.get(0);
        Long baseId = firstId - 1;

        // 刚好一页：5 条，size=5 -> 满页返回游标；再翻一页为空且 nextCursor=null（键集约定）
        SnapshotExportPage exact = snapshotService.exportRange(
                new SnapshotExportRequest(null, null, baseId, 5));
        assertEquals(5, exact.items().size());
        assertNotNull(exact.nextCursor(), "a full page returns a cursor");
        SnapshotExportPage following = snapshotService.exportRange(
                new SnapshotExportRequest(null, null, exact.nextCursor(), 5));
        assertTrue(following.items().isEmpty());
        assertNull(following.nextCursor(), "the page after an exact fit is empty and terminal");

        // 跨页：size=2 拉全，id 严格递增、不漏不重
        List<SnapshotExportItem> all = drain(baseId, null, null, 2);
        List<Long> collected = all.stream().map(SnapshotExportItem::instructionId).toList();
        log.info("cross page: collected={} first={} last={}", collected.size(), collected.get(0),
                collected.get(collected.size() - 1));
        assertEquals(ids, collected);
        assertEquals(ids.size(), collected.stream().distinct().count());

        // 同一段范围重复导出内容完全一致
        List<SnapshotExportItem> again = drain(baseId, null, null, 2);
        assertEquals(describe(all), describe(again), "repeated export must be byte-identical");
    }

    @Test
    void mixedSchemaRange_repeatedExport_isStable() {
        SubmitResult legacy = seed("export-legacy", "7.77", "USD");
        // 受理事件降级为 v1 老结构（缺 channel）
        String legacyPayload = "{\"idempotencyKey\":\"legacy-export\","
                + "\"amount\":\"7.77\",\"currency\":\"USD\"}";
        jdbcTemplate.update("update audit_record set schema_version = 1, payload = ? where instruction_id = ? and seq = 1",
                legacyPayload, legacy.instructionId());
        SubmitResult modern = seed("export-modern", "8.88", "EUR");

        Long baseId = Math.min(legacy.instructionId(), modern.instructionId()) - 1;
        List<SnapshotExportItem> run1 = drain(baseId, null, null, 1);
        List<SnapshotExportItem> run2 = drain(baseId, null, null, 1);

        assertEquals(describe(run1), describe(run2), "mixed-schema range export must be repeatable");
        SnapshotExportItem legacyItem = run1.stream()
                .filter(i -> i.instructionId().equals(legacy.instructionId())).findFirst().orElseThrow();
        assertTrue(legacyItem.ok());
        assertEquals("UNKNOWN", legacyItem.snapshot().attributes().get("channel"));
        log.info("mixed range export stable: legacyId={} modernId={} items={}",
                legacy.instructionId(), modern.instructionId(), run1.size());
    }

    @Test
    void brokenChainAndUnparseablePayload_areReportedAsDataIssues() {
        SubmitResult broken = seed("export-broken", "1.00", "CNY");
        SubmitResult garbage = seed("export-garbage", "2.00", "CNY");

        // 制造链条不连续：删除中间一条推进记录
        settlementService.advance(broken.instructionId(), broken.version(), SettlementStatus.VALIDATED);
        jdbcTemplate.update("delete from audit_record where instruction_id = ? and seq = 2", broken.instructionId());
        // 制造无法解析的 payload
        jdbcTemplate.update("update audit_record set payload = '{not-json' where instruction_id = ? and seq = 1",
                garbage.instructionId());

        Long baseId = Math.min(broken.instructionId(), garbage.instructionId()) - 1;
        List<SnapshotExportItem> items = drain(baseId, null, null, 50);

        SnapshotExportItem brokenItem = items.stream()
                .filter(i -> i.instructionId().equals(broken.instructionId())).findFirst().orElseThrow();
        SnapshotExportItem garbageItem = items.stream()
                .filter(i -> i.instructionId().equals(garbage.instructionId())).findFirst().orElseThrow();

        log.info("data issues visible: broken ok={} code={}; garbage ok={} code={}",
                brokenItem.ok(), brokenItem.errorCode(), garbageItem.ok(), garbageItem.errorCode());
        assertFalse(brokenItem.ok());
        assertEquals("CHAIN_BROKEN", brokenItem.errorCode());
        assertNull(brokenItem.snapshot());
        assertFalse(garbageItem.ok());
        assertEquals("PAYLOAD_UNPARSEABLE", garbageItem.errorCode());
        assertNull(garbageItem.snapshot());
    }

    /** 规范化为稳定字符串，用于断言两次导出逐字段一致。 */
    private String describe(List<SnapshotExportItem> items) {
        StringBuilder sb = new StringBuilder();
        for (SnapshotExportItem i : items) {
            sb.append(i.instructionId()).append('|').append(i.ok()).append('|')
                    .append(i.errorCode()).append('|').append(i.snapshot()).append(';');
        }
        return sb.toString();
    }
}
