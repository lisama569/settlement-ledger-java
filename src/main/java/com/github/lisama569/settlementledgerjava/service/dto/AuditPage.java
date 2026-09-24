package com.github.lisama569.settlementledgerjava.service.dto;

import com.github.lisama569.settlementledgerjava.domain.AuditRecord;

import java.util.List;

/**
 * 游标分页结果：nextCursor 为 null 表示没有更多数据。
 */
public record AuditPage(List<AuditRecord> records,
                        Long nextCursor) {
}
