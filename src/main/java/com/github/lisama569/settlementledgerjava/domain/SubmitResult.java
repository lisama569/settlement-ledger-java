package com.github.lisama569.settlementledgerjava.domain;

/**
 * 受理结果。duplicated=true 表示本次为重复幂等键命中，返回与首次一致的快照。
 */
public record SubmitResult(
        String instructionId,
        SettlementStatus status,
        long version,
        boolean duplicated
) {
}
