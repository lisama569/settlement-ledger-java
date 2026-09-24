package com.github.lisama569.settlementledgerjava.service.dto;

import java.math.BigDecimal;

/**
 * 受理结算指令的入参。
 */
public record SubmitCommand(String idempotencyKey,
                            BigDecimal amount,
                            String currency,
                            String payer,
                            String payee) {
}
