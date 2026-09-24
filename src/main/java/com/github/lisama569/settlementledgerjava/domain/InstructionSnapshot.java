package com.github.lisama569.settlementledgerjava.domain;

import java.math.BigDecimal;

/**
 * 重放/实时处理共用的指令快照（含关键字段，供日志与断言使用）。
 */
public record InstructionSnapshot(
        String instructionId,
        SettlementStatus status,
        long version,
        String debtorAccount,
        String creditorAccount,
        BigDecimal amount,
        String currency,
        String channel,
        String rejectionReason
) {
}
