package com.github.lisama569.settlementledgerjava.domain;

/**
 * 审计事件类型。重放时按事件类型与负载版本做确定性还原。
 */
public enum EventType {
    SUBMITTED,
    STATUS_CHANGED
}
