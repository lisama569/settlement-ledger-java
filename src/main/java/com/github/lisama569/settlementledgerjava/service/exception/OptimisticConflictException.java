package com.github.lisama569.settlementledgerjava.service.exception;

/**
 * 乐观并发冲突：调用方可据此区分“冲突”与其他失败原因。
 */
public class OptimisticConflictException extends RuntimeException {

    private final Long instructionId;
    private final long expectedVersion;

    public OptimisticConflictException(Long instructionId, long expectedVersion, String message) {
        super(message);
        this.instructionId = instructionId;
        this.expectedVersion = expectedVersion;
    }

    public Long getInstructionId() { return instructionId; }
    public long getExpectedVersion() { return expectedVersion; }
}
