package com.github.lisama569.settlementledgerjava.domain;

import java.util.List;

/**
 * 审计事件游标分页结果。nextCursor 为 null 表示无更多数据。
 */
public record EventPage(
        List<SettlementEvent> events,
        Long nextCursor
) {
}
