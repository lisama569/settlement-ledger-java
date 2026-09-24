package com.github.lisama569.settlementledgerjava.web;

import java.math.BigDecimal;

public record SubmitHttpRequest(
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
