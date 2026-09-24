package com.github.lisama569.settlementledgerjava.domain;

import java.math.BigDecimal;

/**
 * 受理命令，必须携带幂等键。channel 缺省由服务层归一化为 UNKNOWN。
 */
public record SubmitCommand(
        String idempotencyKey,
        String instructionId,
        String debtorAccount,
        String creditorAccount,
        BigDecimal amount,
        String currency,
        String channel,
        String submittedBy
) {
}
