package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import com.github.lisama569.settlementledgerjava.repository.SettlementInstructionRepository;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotService;
import com.github.lisama569.settlementledgerjava.service.dto.SettlementSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 按指令完整重建业务快照：
 * 1) 重建结果与实时行一致；
 * 2) 新旧结构混合时完整重建；
 * 3) 老结构缺字段时确定性补齐，重复重建结果一致。
 */
@SpringBootTest
class SnapshotRebuildTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRebuildTest.class);

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SettlementService settlementService;

    @Autowired
    SettlementInstructionRepository instructionRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void rebuiltSnapshot_matchesLiveRead_fullBusinessView() {
        String key = "snap-" + UUID.randomUUID();
        SubmitResult submitted = settlementService.submit(
                new SubmitCommand(key, new BigDecimal("123.45"), "CNY", "payer-x", "payee-y"));

        SettlementSnapshot snapshot = snapshotService.rebuild(submitted.instructionId());
        SettlementInstruction live = instructionRepository.findById(submitted.instructionId()).orElseThrow();

        log.info("rebuilt: idempotencyKey={} amount={} currency={} payer={} payee={} status={} version={} lastChangedAt={}",
                key, snapshot.amount(), snapshot.currency(), snapshot.payer(), snapshot.payee(),
                snapshot.status(), snapshot.version(), snapshot.lastChangedAt());

        assertEquals(live.getId(), snapshot.instructionId());
        assertEquals(live.getIdempotencyKey(), snapshot.idempotencyKey());
        assertEquals(0, live.getAmount().compareTo(snapshot.amount()));
        assertEquals(live.getCurrency(), snapshot.currency());
        assertEquals(live.getPayer(), snapshot.payer());
        assertEquals(live.getPayee(), snapshot.payee());
        assertEquals(live.getStatus(), snapshot.status());
        assertEquals(live.getVersion(), snapshot.version());
        assertEquals(live.getUpdatedAt(), snapshot.lastChangedAt());
        assertEquals(live.getCreatedAt(), snapshot.receivedAt());
        assertEquals("API", snapshot.attributes().get("channel"));
    }

    @Test
    void rebuiltSnapshot_mixedOldAndNewSchema_isCompleteAndDeterministic() {
        String key = "snap-mixed-" + UUID.randomUUID();
        SubmitResult submitted = settlementService.submit(
                new SubmitCommand(key, new BigDecimal("10.00"), "CNY", "payer-a", "payee-b"));
        Long id = submitted.instructionId();

        // 把受理事件改写成结构变更前的 v1 payload：缺 channel / payer / payee
        String legacyPayload = "{\"idempotencyKey\":\"" + key + "\","
                + "\"amount\":\"10.00\",\"currency\":\"CNY\"}";
        jdbcTemplate.update("update audit_record set schema_version = 1, payload = ? where instruction_id = ? and seq = 1",
                legacyPayload, id);
        log.info("seeded legacy v1 submit event: idempotencyKey={}", key);

        // 后续推进事件是当前 v2 结构 -> 同一指令新旧结构混合
        settlementService.advance(id, submitted.version(),
                com.github.lisama569.settlementledgerjava.domain.SettlementStatus.VALIDATED);

        SettlementSnapshot first = snapshotService.rebuild(id);
        SettlementSnapshot second = snapshotService.rebuild(id);
        SettlementInstruction live = instructionRepository.findById(id).orElseThrow();

        log.info("mixed-schema snapshot: idempotencyKey={} status={} version={} channelAttr={} attrs={}",
                key, first.status(), first.version(), first.attributes().get("channel"), first.attributes());

        // 实时字段仍然权威且一致
        assertEquals(live.getStatus(), first.status());
        assertEquals(live.getVersion(), first.version());
        assertEquals(live.getAmount(), first.amount());
        // 老结构缺字段按固定规则补齐，不报错、不丢字段
        assertEquals("UNKNOWN", first.attributes().get("channel"), "missing v1 field deterministically defaulted");
        assertEquals(key, first.attributes().get("idempotencyKey"), "existing fields preserved");
        assertEquals("CNY", first.attributes().get("currency"));
        assertEquals("10.00", first.attributes().get("amount"));
        // 核心收付方来自实时行，不因 payload 缺字段而错位
        assertEquals("payer-a", first.payer());
        assertEquals("payee-b", first.payee());
        // 同一份老数据重复重建完全一致
        assertEquals(first, second);
        assertEquals(first.attributes(), second.attributes());
    }
}
