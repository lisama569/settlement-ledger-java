package com.github.lisama569.settlementledgerjava.web;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

public record SubmitHttpResponse(
        String instructionId,
        SettlementStatus status,
        long version,
        boolean duplicated
) {
}
