package com.github.lisama569.settlementledgerjava.web;

import com.github.lisama569.settlementledgerjava.domain.InstructionSnapshot;

import java.math.BigDecimal;

public record SnapshotHttpResponse(
        String instructionId,
        String status,
        long version,
        String debtorAccount,
        String creditorAccount,
        BigDecimal amount,
        String currency,
        String channel,
        String rejectionReason
) {
    public static SnapshotHttpResponse from(InstructionSnapshot s) {
        return new SnapshotHttpResponse(s.instructionId(), s.status().name(), s.version(),
                s.debtorAccount(), s.creditorAccount(), s.amount(), s.currency(),
                s.channel(), s.rejectionReason());
    }
}
