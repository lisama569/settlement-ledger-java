package com.github.lisama569.settlementledgerjava.service.dto;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

/**
 * 状态推进结果。
 */
public record AdvanceResult(Long instructionId,
                            SettlementStatus status,
                            long version) {
}
