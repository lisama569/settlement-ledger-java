package com.github.lisama569.settlementledgerjava.error;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

public class IdempotencyConflictException extends SettlementException {
    private final String idempotencyKey;
    private final String existingInstructionId;
    private final SettlementStatus existingStatus;

    public IdempotencyConflictException(String idempotencyKey, String existingInstructionId, SettlementStatus existingStatus) {
        super(ErrorCode.IDEMPOTENCY_KEY_DUPLICATED_CONFLICT,
                "idempotency key '" + idempotencyKey + "' was already used for instruction "
                        + existingInstructionId + " with a different request payload; existingStatus=" + existingStatus);
        this.idempotencyKey = idempotencyKey;
        this.existingInstructionId = existingInstructionId;
        this.existingStatus = existingStatus;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getExistingInstructionId() { return existingInstructionId; }
    public SettlementStatus getExistingStatus() { return existingStatus; }
}
