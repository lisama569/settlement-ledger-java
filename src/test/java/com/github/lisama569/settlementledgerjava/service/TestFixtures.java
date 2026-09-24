package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.SubmitCommand;

import java.math.BigDecimal;

final class TestFixtures {

    private TestFixtures() {
    }

    static SubmitCommand submit(String idempotencyKey, String instructionId) {
        return new SubmitCommand(idempotencyKey, instructionId,
                "DEBT-0001", "CRED-0001", new BigDecimal("100.50"), "CNY",
                "WEB", "tester");
    }
}
