package com.github.lisama569.settlementledgerjava.service.dto;

import java.util.List;

/**
 * 批量导出的一页结果。nextCursor 为 null 表示范围内没有更多数据。
 */
public record SnapshotExportPage(List<SnapshotExportItem> items,
                                 Long nextCursor) {
}
