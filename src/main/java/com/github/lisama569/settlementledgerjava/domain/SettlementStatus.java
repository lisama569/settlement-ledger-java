package com.github.lisama569.settlementledgerjava.domain;

/**
 * 结算指令状态机：
 * ACCEPTED -> PROCESSING -> SETTLED
 *                 |
 *                 v
 *              REJECTED
 */
public enum SettlementStatus {
    ACCEPTED,
    PROCESSING,
    SETTLED,
    REJECTED
}
