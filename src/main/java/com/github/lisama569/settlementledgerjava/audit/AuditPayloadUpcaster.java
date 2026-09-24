package com.github.lisama569.settlementledgerjava.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 审计 payload 结构演进处理：读取历史记录时按 schemaVersion 确定性补齐字段，
 * 而不是静默错列或丢字段。
 *
 * v1 -> v2：补充 channel 字段（默认 "UNKNOWN"）。
 */
@Component
public class AuditPayloadUpcaster {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonNode upcast(int schemaVersion, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            int version = schemaVersion;
            while (version < CURRENT_SCHEMA_VERSION) {
                node = upcastOne(version, node);
                version++;
            }
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upcast audit payload (schemaVersion=" + schemaVersion + ")", e);
        }
    }

    private JsonNode upcastOne(int fromVersion, JsonNode node) {
        if (fromVersion == 1 && node instanceof ObjectNode obj && !obj.has("channel")) {
            obj.put("channel", "UNKNOWN");
        }
        return node;
    }
}
