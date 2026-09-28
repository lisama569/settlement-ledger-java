package com.github.lisama569.settlementledgerjava.service.dto;

/**
 * 归档结果。archivedCount 为本次新归档记录数；同一归档批次（runId）重试只生效一次。
 */
public record ArchiveResult(String runId,
                            long archivedCount,
                            boolean completed,
                            boolean alreadyApplied) {
}
