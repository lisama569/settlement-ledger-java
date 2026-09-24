package com.github.lisama569.settlementledgerjava.domain;

/**
 * 结算指令状态机：RECEIVED -> VALIDATED -> SETTLED / FAILED
 */
public enum SettlementStatus {
    RECEIVED,
    VALIDATED,
    SETTLED,
    FAILED
}
