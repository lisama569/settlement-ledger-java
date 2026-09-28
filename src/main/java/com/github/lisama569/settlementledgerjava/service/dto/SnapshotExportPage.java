package com.github.lisama569.settlementledgerjava.service.dto;

import java.util.List;

/**
 * 按指令 id 键集分页的对账导出结果。nextCursor 为下一页起始指令 id（不含），
 * null 表示范围内没有更多数据。
 */
public record SnapshotExportPage(List<SnapshotExportEntry> entries,
                                 Long nextCursor) {
}
