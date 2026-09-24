package com.github.lisama569.settlementledgerjava.service.exception;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

/**
 * 非法状态迁移：与并发冲突可区分的另一类失败原因。
 */
public class IllegalTransitionException extends RuntimeException {

    private final SettlementStatus from;
    private final SettlementStatus to;

    public IllegalTransitionException(SettlementStatus from, SettlementStatus to) {
        super("Illegal transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public SettlementStatus getFrom() { return from; }
    public SettlementStatus getTo() { return to; }
}
