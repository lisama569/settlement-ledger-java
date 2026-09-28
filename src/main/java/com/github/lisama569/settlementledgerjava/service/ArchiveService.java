package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.service.dto.ArchiveResult;

import java.time.Instant;

/**
 * 审计历史归档：把较早的在线审计记录搬到归档表。
 * 归档不改变重建结果；与在线写入并发时不漏不重；失败重试后只生效一次。
 */
public interface ArchiveService {

    /**
     * 归档 created_at 早于 cutoff 的记录，按批处理直到没有候选数据。
     */
    ArchiveResult archiveBefore(Instant cutoff, int batchSize);

    /**
     * 归档 id 不超过 maxIdInclusive 的记录（便于按明确边界做并发/对账验证）。
     */
    ArchiveResult archiveUpToId(long maxIdInclusive, int batchSize);
}
