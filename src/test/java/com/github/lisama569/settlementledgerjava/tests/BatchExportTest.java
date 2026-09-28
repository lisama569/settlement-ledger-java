package com.github.lisama569.settlementledgerjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.lisama569.settlementledgerjava.audit.AuditPayloadUpcaster;
import com.github.lisama569.settlementledgerjava.repository.SettlementInstructionRepository;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotRebuildService;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportEntry;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportPage;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.tests.support.LegacyAuditFixtures;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 按范围批量重建并导出对账快照：
 * 空范围 / 刚好一页 / 跨页边界；新旧结构混合时重复导出内容完全一致；
 * 历史链条不连续 / payload 无法解析以 DATA_PROBLEM 显式返回，不静默跳过。
 */
@SpringBootTest
class BatchExportTest {

    private static final Logger log = LoggerFactory.getLogger(BatchExportTest.class);

    @Autowired
    SnapshotRebuildService snapshotRebuildService;
    @Autowired
    SettlementService settlementService;
    @Autowired
    SettlementInstructionRepository instructionRepository;
    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper canonicalMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void emptyRange_returnsEmptyEntries_nullCursor() {
        SnapshotExportPage page = snapshotRebuildService.exportBatch(Long.MAX_VALUE, null, null, 10);
        log.info("empty range: size={} nextCursor={}", page.entries().size(), page.nextCursor());
        assertTrue(page.entries().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void exactlyOnePage_andCrossPage_boundaries() {
        String tag = "export-page-" + UUID.randomUUID() + "-";
        for (int i = 0; i < 5; i++) {
            settlementService.submit(new SubmitCommand(tag + i, new BigDecimal("1.00"), "CNY", "a", "b"));
        }
        // 只取本 tag 的 id 范围，排除其他测试数据
        List<Long> mine = filterTagIds(tag);
        assertEquals(5, mine.size());

        // size=5 时应“刚好一页”：首页即覆盖范围全部 5 条且其后无更多本范围数据
        SnapshotExportPage onePage = snapshotRebuildService.exportBatch(mine.get(0) - 1, null, null, 5);
        long inRange = onePage.entries().stream()
                .filter(e -> e.instructionId() >= mine.get(0) && e.instructionId() <= mine.get(4)).count();
        log.info("exact one page: returned={} inRange={} nextCursor={}",
                onePage.entries().size(), inRange, onePage.nextCursor());
        // 范围内恰好 5 条；由于用严格大于游标分页，首页后应无更多本范围数据
        assertEquals(5, inRange);

        // size=2 跨页：游标遍历，严格递增、无漏无重
        List<Long> seenIds = new ArrayList<>();
        Long cursor = mine.get(0) - 1;
        int maxPages = 100;
        while (seenIds.size() < 5) {
            SnapshotExportPage page = snapshotRebuildService.exportBatch(cursor, null, null, 2);
            for (SnapshotExportEntry e : page.entries()) {
                if (e.instructionId() >= mine.get(0) && e.instructionId() <= mine.get(4)) {
                    seenIds.add(e.instructionId());
                }
            }
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
            assertTrue(maxPages-- > 0, "pagination must terminate");
        }
        log.info("cross-page collected: {}", seenIds);
        assertEquals(mine, seenIds, "cross-page export must return the exact range, no gap/dup");
    }

    @Test
    void repeatedExport_mixedOldAndNew_isByteStable_andProblemsAreExplicit() throws Exception {
        String tag = "export-mix-" + UUID.randomUUID() + "-";

        // 新结构 v3
        settlementService.submit(new SubmitCommand(tag + "new", new BigDecimal("2.50"), "USD", "p", "q"));
        // 老结构 v1（缺 channel/payer/payee）
        LegacyAuditFixtures.seedSubmitted(jdbc, instructionRepository, tag + "v1", 1,
                "{\"idempotencyKey\":\"" + tag + "v1\",\"amount\":\"4.40\",\"currency\":\"CNY\"}");
        // 老结构 v2（缺 businessType）
        LegacyAuditFixtures.seedSubmitted(jdbc, instructionRepository, tag + "v2", 2,
                "{\"idempotencyKey\":\"" + tag + "v2\",\"amount\":\"5.50\",\"currency\":\"EUR\","
                        + "\"payer\":\"x\",\"payee\":\"y\",\"channel\":\"BATCH\"}");
        // 无法解析
        Long badId = LegacyAuditFixtures.seedSubmitted(jdbc, instructionRepository, tag + "bad",
                AuditPayloadUpcaster.CURRENT_SCHEMA_VERSION, "not-json");
        // 链条断裂
        Long brokenId = LegacyAuditFixtures.seedBrokenChain(jdbc, instructionRepository, tag + "broken");

        List<Long> mine = filterTagIds(tag);
        long minId = mine.get(0) - 1;
        long maxId = mine.get(mine.size() - 1);

        List<SnapshotExportEntry> first = exportRangeOnce(minId, maxId, 2);
        List<SnapshotExportEntry> second = exportRangeOnce(minId, maxId, 3); // 不同页大小
        List<SnapshotExportEntry> third = exportRangeOnce(minId, maxId, 100);

        // 问题条目显式存在，且仍占据范围中的位置（没有被静默跳过）
        List<Long> problemIds = first.stream().filter(SnapshotExportEntry::isProblem)
                .map(SnapshotExportEntry::instructionId).sorted().toList();
        log.info("problems in export: {}", problemIds);
        assertTrue(problemIds.contains(badId));
        assertTrue(problemIds.contains(brokenId));
        SnapshotExportEntry badEntry = first.stream().filter(e -> e.instructionId().equals(badId)).findFirst().orElseThrow();
        SnapshotExportEntry brokenEntry = first.stream().filter(e -> e.instructionId().equals(brokenId)).findFirst().orElseThrow();
        assertEquals("AUDIT_PAYLOAD_UNPARSEABLE", badEntry.errorCode());
        assertEquals("AUDIT_CHAIN_BROKEN", brokenEntry.errorCode());
        assertNull(badEntry.snapshot());

        // 条目总数 = 正常 + 问题，范围中一条不少
        assertEquals(mine.size(), first.size(), "problems occupy a slot instead of being silently skipped");

        // 重复导出（不同分页大小），内容完全一致（按规范 JSON 比较）
        String json1 = canonical(first, minId, maxId);
        String json2 = canonical(second, minId, maxId);
        String json3 = canonical(third, minId, maxId);
        log.info("repeated export canonical lengths: {} {} {}", json1.length(), json2.length(), json3.length());
        assertEquals(json1, json2, "same range must export identical content regardless of page size");
        assertEquals(json1, json3, "repeated export must be byte-stable");
    }

    private List<SnapshotExportEntry> exportRangeOnce(long afterId, long maxId, int size) {
        List<SnapshotExportEntry> all = new ArrayList<>();
        Long cursor = afterId;
        int guard = 1000;
        while (true) {
            SnapshotExportPage page = snapshotRebuildService.exportBatch(cursor, null, null, size);
            for (SnapshotExportEntry e : page.entries()) {
                if (e.instructionId() <= maxId) {
                    all.add(e);
                }
            }
            if (page.nextCursor() == null || page.nextCursor() > maxId) {
                break;
            }
            cursor = page.nextCursor();
            assertTrue(guard-- > 0);
        }
        return all;
    }

    /** 通过 SQL 按 idempotencyKey 前缀找本测试数据的 id，避免受其他测试数据影响。 */
    private List<Long> filterTagIds(String tag) {
        return jdbc.queryForList(
                "select id from settlement_instruction where idempotency_key like ? order by id asc",
                Long.class, tag + "%");
    }

    private String canonical(List<SnapshotExportEntry> entries, long minId, long maxId) throws Exception {
        List<SnapshotExportEntry> inRange = entries.stream()
                .filter(e -> e.instructionId() > minId && e.instructionId() <= maxId)
                .sorted((a, b) -> Long.compare(a.instructionId(), b.instructionId()))
                .collect(Collectors.toList());
        return canonicalMapper.writerWithDefaultPrettyPrinter().writeValueAsString(inRange);
    }
}
