package com.github.lisama569.settlementledgerjava.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.settlementledgerjava.domain.EventPayload;

/**
 * 事件负载编解码与跨版本确定性归一化。
 */
public final class EventPayloads {

    /** 当前写入负载的结构版本。 */
    public static final int CURRENT_PAYLOAD_VERSION = 2;

    /** 历史版本缺失字段时的确定性默认渠道。 */
    public static final String DEFAULT_CHANNEL = "UNKNOWN";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private EventPayloads() {
    }

    public static String encode(EventPayload payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to encode event payload", e);
        }
    }

    /**
     * 按行内 payload_version 解码并归一化：
     * 新增字段在历史负载中缺失时回填确定性默认值，绝不静默为 null 参与业务语义。
     */
    public static EventPayload decode(int payloadVersion, String json) {
        EventPayload raw;
        try {
            raw = MAPPER.readValue(json, EventPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to decode event payload: " + json, e);
        }
        return normalize(payloadVersion, raw);
    }

    static EventPayload normalize(int payloadVersion, EventPayload raw) {
        String channel = raw.channel();
        if (channel == null || channel.isBlank()) {
            channel = DEFAULT_CHANNEL;
        }
        String currency = raw.currency() == null ? null : raw.currency();
        String reason = raw.reason() == null ? null : raw.reason();
        return new EventPayload(
                raw.instructionId(), raw.debtorAccount(), raw.creditorAccount(),
                raw.amount(), currency, raw.submittedBy(), channel,
                raw.fromStatus(), raw.toStatus(), reason);
    }
}
