package com.github.lisama569.settlementledgerjava.service.dto;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

/**
 * 受理结果。duplicate=true 表示命中幂等键、返回的是首次提交的结果快照。
 */
public record SubmitResult(Long instructionId,
                           String idempotencyKey,
                           SettlementStatus status,
                           long version,
                           boolean duplicate) {
}
