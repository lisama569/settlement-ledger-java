package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.service.dto.ArchiveResult;

import java.time.Instant;

/**
 * 审计历史归档：把较早的记录移出热表，归档后重建结果不变，同一 runId 重试只生效一次。
 */
public interface ArchiveService {

    ArchiveResult archive(String runId, Instant cutoffAt);
}
