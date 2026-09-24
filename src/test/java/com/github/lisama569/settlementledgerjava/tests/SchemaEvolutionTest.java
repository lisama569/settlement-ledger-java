package com.github.lisama569.settlementledgerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lisama569.settlementledgerjava.audit.AuditPayloadUpcaster;
import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构演进：历史 v1 记录缺少 channel 字段，读取时须按确定性语义补齐，
 * 不得静默错列或丢字段。
 */
@SpringBootTest
class SchemaEvolutionTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaEvolutionTest.class);

    @Autowired
    AuditRecordRepository auditRecordRepository;

    @Autowired
    AuditPayloadUpcaster upcaster;

    @Test
    void legacyV1Payload_isUpcastDeterministically() {
        // 模拟历史遗留的 v1 审计记录（无 channel 字段）
        String legacyPayload = "{\"idempotencyKey\":\"legacy-1\",\"amount\":\"9.99\",\"currency\":\"CNY\"}";
        AuditRecord legacy = auditRecordRepository.save(new AuditRecord(
                -1L, 1L, null, SettlementStatus.RECEIVED, "SUBMITTED", 1, legacyPayload, Instant.now()));
        log.info("legacy record: id={} schemaVersion={} payload={}",
                legacy.getId(), legacy.getSchemaVersion(), legacy.getPayload());

        AuditRecord loaded = auditRecordRepository.findById(legacy.getId()).orElseThrow();
        assertEquals(1, loaded.getSchemaVersion());

        JsonNode upcasted = upcaster.upcast(loaded.getSchemaVersion(), loaded.getPayload());
        log.info("upcasted payload: {}", upcasted);
        assertEquals("UNKNOWN", upcasted.get("channel").asText(), "missing field filled deterministically");
        assertEquals("legacy-1", upcasted.get("idempotencyKey").asText(), "existing fields preserved");
        assertEquals("9.99", upcasted.get("amount").asText());
        assertEquals("CNY", upcasted.get("currency").asText());

        // 确定性：重复补齐结果一致
        JsonNode again = upcaster.upcast(loaded.getSchemaVersion(), loaded.getPayload());
        assertEquals(upcasted, again);
    }

    @Test
    void currentVersionPayload_passesThroughUnchanged() {
        String v2Payload = "{\"idempotencyKey\":\"now-1\",\"channel\":\"API\"}";
        JsonNode node = upcaster.upcast(AuditPayloadUpcaster.CURRENT_SCHEMA_VERSION, v2Payload);
        log.info("current payload passthrough: {}", node);
        assertEquals("API", node.get("channel").asText());
    }
}
