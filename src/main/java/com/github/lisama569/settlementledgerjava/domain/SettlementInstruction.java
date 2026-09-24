package com.github.lisama569.settlementledgerjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算指令当前态（由事件流物化得到）。
 */
public record SettlementInstruction(
        String instructionId,
        String debtorAccount,
        String creditorAccount,
        BigDecimal amount,
        String currency,
        String channel,
        SettlementStatus status,
        long version,
        String submittedBy,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt
) {
}
