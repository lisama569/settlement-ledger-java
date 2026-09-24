package com.github.lisama569.settlementledgerjava.domain;

import java.time.Instant;

/**
 * 不可变审计事件。seq_no 为全局单调游标，payload_version 标记负载结构版本。
 */
public record SettlementEvent(
        long seqNo,
        String instructionId,
        EventType eventType,
        int payloadVersion,
        String payload,
        Instant occurredAt
) {
}
