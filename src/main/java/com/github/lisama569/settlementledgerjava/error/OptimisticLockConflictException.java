package com.github.lisama569.settlementledgerjava.error;

public class OptimisticLockConflictException extends SettlementException {
    private final String instructionId;
    private final long expectedVersion;
    private final long actualVersion;

    public OptimisticLockConflictException(String instructionId, long expectedVersion, long actualVersion) {
        super(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                "optimistic lock conflict for instruction " + instructionId
                        + ": expectedVersion=" + expectedVersion + " actualVersion=" + actualVersion);
        this.instructionId = instructionId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String getInstructionId() { return instructionId; }
    public long getExpectedVersion() { return expectedVersion; }
    public long getActualVersion() { return actualVersion; }
}
