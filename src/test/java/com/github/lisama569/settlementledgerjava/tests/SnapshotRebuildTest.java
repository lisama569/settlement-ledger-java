package com.github.lisama569.settlementledgerjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.lisama569.settlementledgerjava.audit.AuditPayloadUpcaster;
import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.repository.SettlementInstructionRepository;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotRebuildService;
import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import com.github.lisama569.settlementledgerjava.service.exception.AuditPayloadUnparseableException;
import com.github.lisama569.settlementledgerjava.tests.support.LegacyAuditFixtures;
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
 * 完整业务快照重建：
 * - 新旧结构混合时可完整重建，重建结果与实时读取一致；
 * - 老结构（v1/v2）缺字段时按固定规则确定性补齐，多次重建结果相同。
 */
@SpringBootTest
class SnapshotRebuildTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRebuildTest.class);

    @Autowired
    SnapshotRebuildService snapshotRebuildService;
    @Autowired
    SettlementService settlementService;
    @Autowired
    SettlementInstructionRepository instructionRepository;
    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void rebuiltSnapshot_matchesLiveRead_acrossFullLifecycle() {
        String key = "snap-live-" + UUID.randomUUID();
        SubmitResult submitted = settlementService.submit(
                new SubmitCommand(key, new BigDecimal("120.0000"), "CNY", "ivy", "jack"));
        AdvanceResult validated = settlementService.advance(
                submitted.instructionId(), submitted.version(), SettlementStatus.VALIDATED);
        AdvanceResult settled = settlementService.advance(
                submitted.instructionId(), validated.version(), SettlementStatus.SETTLED);

        InstructionSnapshot snapshot = snapshotRebuildService.rebuild(submitted.instructionId());
        SettlementInstruction live = instructionRepository.findById(submitted.instructionId()).orElseThrow();

        log.info("rebuilt snapshot: idempotencyKey={} instructionId={} amount={} currency={} payer={} payee={} channel={} status={} version={} lastChangedAt={} attributes={}",
                snapshot.idempotencyKey(), snapshot.instructionId(), snapshot.amount(), snapshot.currency(),
                snapshot.payer(), snapshot.payee(), snapshot.channel(), snapshot.status(), snapshot.version(),
                snapshot.lastChangedAt(), snapshot.attributes());

        assertEquals(key, snapshot.idempotencyKey());
        assertEquals(0, new BigDecimal("120.0000").compareTo(snapshot.amount()));
        assertEquals("CNY", snapshot.currency());
        assertEquals("ivy", snapshot.payer());
        assertEquals("jack", snapshot.payee());
        assertEquals("API", snapshot.channel());
        assertEquals(SettlementStatus.SETTLED, snapshot.status());
        assertEquals(settled.version(), snapshot.version());
        assertEquals(2L, snapshot.version());
        assertEquals(live.getStatus(), snapshot.status());
        assertEquals(live.getVersion(), snapshot.version());
        assertEquals(live.getUpdatedAt(), snapshot.lastChangedAt());
        assertEquals("PAYMENT", snapshot.attributes().get("businessType"));
    }

    @Test
    void legacyV1MissingFields_areFilledDeterministically_andStableAcrossRebuilds() throws Exception {
        String key = "snap-legacy-v1-" + UUID.randomUUID();
        // v1 老批次：无 channel/payer/payee；v3 才有的 businessType 也缺。
        Long id = LegacyAuditFixtures.seedSubmitted(jdbc, instructionRepository, key, 1,
                "{\"idempotencyKey\":\"" + key + "\",\"amount\":\"9.99\",\"currency\":\"CNY\"}");

        InstructionSnapshot first = snapshotRebuildService.rebuild(id);
        InstructionSnapshot second = snapshotRebuildService.rebuild(id);

        log.info("legacy v1 rebuilt: instructionId={} payer={} payee={} channel={} attributes={}",
                id, first.payer(), first.payee(), first.channel(), first.attributes());

        assertEquals("UNKNOWN", first.payer(), "missing payer filled by fixed rule");
        assertEquals("UNKNOWN", first.payee(), "missing payee filled by fixed rule");
        assertEquals("UNKNOWN", first.channel(), "v1 missing channel filled");
        assertEquals(0, new BigDecimal("9.99").compareTo(first.amount()));
        assertEquals("CNY", first.currency());
        assertEquals(SettlementStatus.RECEIVED, first.status());
        assertEquals(0L, first.version());
        assertEquals("PAYMENT", first.attributes().get("businessType"), "v3 missing businessType filled");

        // 确定性：同一份老数据重建任意次，序列化结果完全一致。
        assertEquals(objectMapper.writeValueAsString(first), objectMapper.writeValueAsString(second));
    }

    @Test
    void legacyV2PartialFields_andCurrentV3_mixStably() throws Exception {
        // v2 老记录：有 channel，无 businessType
        String key2 = "snap-legacy-v2-" + UUID.randomUUID();
        Long v2id = LegacyAuditFixtures.seedSubmitted(jdbc, instructionRepository, key2, 2,
                "{\"idempotencyKey\":\"" + key2 + "\",\"amount\":\"1.20\",\"currency\":\"USD\","
                        + "\"payer\":\"a\",\"payee\":\"b\",\"channel\":\"BATCH\"}");
        InstructionSnapshot v2 = snapshotRebuildService.rebuild(v2id);
        assertEquals("BATCH", v2.channel());
        assertEquals("PAYMENT", v2.attributes().get("businessType"));
        assertEquals("a", v2.payer());

        // 新结构 v3 完整字段
        String key3 = "snap-now-v3-" + UUID.randomUUID();
        SubmitResult now = settlementService.submit(
                new SubmitCommand(key3, new BigDecimal("7.00"), "EUR", "c", "d"));
        InstructionSnapshot v3 = snapshotRebuildService.rebuild(now.instructionId());
        assertEquals("API", v3.channel());
        assertEquals("PAYMENT", v3.attributes().get("businessType"));

        // 两者并存，互不影响，各自重复重建稳定。
        assertEquals(objectMapper.writeValueAsString(v2),
                objectMapper.writeValueAsString(snapshotRebuildService.rebuild(v2id)));
        assertEquals(objectMapper.writeValueAsString(v3),
                objectMapper.writeValueAsString(snapshotRebuildService.rebuild(now.instructionId())));
    }

    @Test
    void unparseablePayload_raisesDistinguishableDataProblem() {
        String key = "snap-bad-json-" + UUID.randomUUID();
        Long id = LegacyAuditFixtures.seedSubmitted(jdbc, instructionRepository, key,
                AuditPayloadUpcaster.CURRENT_SCHEMA_VERSION, "this-is-not-json");
        AuditPayloadUnparseableException e = assertThrows(AuditPayloadUnparseableException.class,
                () -> snapshotRebuildService.rebuild(id));
        log.info("unparseable rejected: instructionId={} errorCode={} message={}",
                id, e.getErrorCode(), e.getMessage());
        assertEquals("AUDIT_PAYLOAD_UNPARSEABLE", e.getErrorCode());
        assertEquals(id, e.getInstructionId());
    }
}
