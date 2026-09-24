package com.github.lisama569.settlementledgerjava.error;

/**
 * 可被调用方区分的失败原因码。
 */
public enum ErrorCode {
    IDEMPOTENCY_KEY_DUPLICATED_CONFLICT,
    OPTIMISTIC_LOCK_CONFLICT,
    INSTRUCTION_NOT_FOUND,
    ILLEGAL_STATUS_TRANSITION,
    VALIDATION_FAILED
}
