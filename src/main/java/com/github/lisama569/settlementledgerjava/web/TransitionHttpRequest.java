package com.github.lisama569.settlementledgerjava.web;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

public record TransitionHttpRequest(
        Long expectedVersion,
        SettlementStatus targetStatus,
        String reason
) {
}
