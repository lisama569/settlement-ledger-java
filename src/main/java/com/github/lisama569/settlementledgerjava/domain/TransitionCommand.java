package com.github.lisama569.settlementledgerjava.domain;

/**
 * 状态推进命令。expectedVersion 为调用方读到的版本号，用于乐观并发判定。
 */
public record TransitionCommand(
        String instructionId,
        long expectedVersion,
        SettlementStatus targetStatus,
        String reason
) {
}
