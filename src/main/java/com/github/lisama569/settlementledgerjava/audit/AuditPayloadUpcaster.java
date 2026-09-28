package com.github.lisama569.settlementledgerjava.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 审计 payload 结构演进处理：读取历史记录时按 schemaVersion 逐级、确定性补齐字段，
 * 而不是静默错列或丢字段。补齐只发生在读取路径，不回写历史原文，因此同一条老数据
 * 无论重建多少次、是否已归档，得到的结果都完全一致。
 *
 * <p>兼容范围（固定补齐规则）：
 * <ul>
 *   <li>v1 -&gt; v2：补 {@code channel}（默认 {@code UNKNOWN}）；老批次缺失的
 *       {@code payer}/{@code payee}/{@code currency} 补 {@code UNKNOWN}，
 *       缺失的 {@code amount} 补 {@code 0}（数值缺失的固定占位，对账方可显式识别）。</li>
 *   <li>v2 -&gt; v3：补 {@code businessType}（默认 {@code PAYMENT}）。</li>
 * </ul>
 * 已有字段原样保留，绝不按位置猜列。
 */
@Component
public class AuditPayloadUpcaster {

    public static final int CURRENT_SCHEMA_VERSION = 3;

    private static final String UNKNOWN = "UNKNOWN";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonNode upcast(int schemaVersion, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("payload is not a JSON object");
            }
            int version = schemaVersion;
            while (version < CURRENT_SCHEMA_VERSION) {
                node = upcastOne(version, (ObjectNode) node);
                version++;
            }
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upcast audit payload (schemaVersion=" + schemaVersion + ")", e);
        }
    }

    private ObjectNode upcastOne(int fromVersion, ObjectNode node) {
        switch (fromVersion) {
            case 1 -> {
                putIfMissing(node, "channel", UNKNOWN);
                putIfMissing(node, "payer", UNKNOWN);
                putIfMissing(node, "payee", UNKNOWN);
                putIfMissing(node, "currency", UNKNOWN);
                if (!node.has("amount") || !node.get("amount").isValueNode()) {
                    node.put("amount", BigDecimal.ZERO.toPlainString());
                }
            }
            case 2 -> putIfMissing(node, "businessType", "PAYMENT");
            default -> throw new IllegalStateException("Unknown schema version: " + fromVersion);
        }
        return node;
    }

    private void putIfMissing(ObjectNode node, String field, String fixedValue) {
        if (!node.has(field) || node.get(field).isNull()) {
            node.put(field, fixedValue);
        }
    }
}
