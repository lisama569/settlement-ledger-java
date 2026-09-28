package com.github.lisama569.settlementledgerjava.service.dto;

/**
 * 一次归档调用的结果。archivedCount 为本次真正搬迁的记录数；
 * 重复执行（含失败重试成功后再次执行）时为 0，体现归档只生效一次。
 */
public record ArchiveResult(long archivedCount,
                            Long maxIdInclusive,
                            boolean more) {
}
