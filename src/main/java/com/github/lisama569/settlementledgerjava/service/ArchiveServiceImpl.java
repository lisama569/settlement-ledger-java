package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.ArchivedAuditRecord;
import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import com.github.lisama569.settlementledgerjava.repository.ArchivedAuditRecordRepository;
import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;
import com.github.lisama569.settlementledgerjava.service.dto.ArchiveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Service
public class ArchiveServiceImpl implements ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveServiceImpl.class);

    private final AuditRecordRepository auditRecordRepository;
    private final ArchivedAuditRecordRepository archivedRepository;
    private final TransactionTemplate transactionTemplate;

    public ArchiveServiceImpl(AuditRecordRepository auditRecordRepository,
                              ArchivedAuditRecordRepository archivedRepository,
                              TransactionTemplate transactionTemplate) {
        this.auditRecordRepository = auditRecordRepository;
        this.archivedRepository = archivedRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 归档 created_at 早于 cutoff 的记录。先一次性确定候选 id 上界，再按该固定边界搬迁，
     * 归档进行期间新受理/推进的记录 id 更大、created_at 更新，绝不混入本批。
     */
    @Override
    public ArchiveResult archiveBefore(Instant cutoff, int batchSize) {
        List<Long> candidateIds = auditRecordRepository.findIdsByCreatedAtBefore(cutoff,
                PageRequest.of(0, Integer.MAX_VALUE));
        if (candidateIds.isEmpty()) {
            log.info("archive: no candidates before cutoff={}", cutoff);
            return new ArchiveResult(0L, null, false);
        }
        // 候选集合即确定的归档范围（最大 id 为上界），此后只针对 id <= maxId 处理。
        long maxId = candidateIds.get(candidateIds.size() - 1);
        return runBatches(maxId, batchSize);
    }

    @Override
    public ArchiveResult archiveUpToId(long maxIdInclusive, int batchSize) {
        return runBatches(maxIdInclusive, batchSize);
    }

    private ArchiveResult runBatches(long maxIdInclusive, int batchSize) {
        int size = batchSize <= 0 ? 500 : batchSize;
        long total = 0L;
        while (true) {
            // 每批在独立事务内：复制（保留原 id）+ 删除，原子提交。
            // 失败整批回滚，在线表记录不丢；重试时同一批重新执行，已归档 id 被跳过，不重复。
            Long movedBoxed = transactionTemplate.execute(tx -> archiveOneBatch(maxIdInclusive, size));
            long moved = movedBoxed == null ? 0L : movedBoxed;
            total += moved;
            if (moved < size) {
                log.info("archive finished: maxIdInclusive={} archivedCount={} lastBatch={}",
                        maxIdInclusive, total, moved);
                return new ArchiveResult(total, maxIdInclusive, false);
            }
            log.info("archive batch: maxIdInclusive={} batchMoved={} totalSoFar={}", maxIdInclusive, moved, total);
        }
    }

    /**
     * 搬迁一批仍在在线表中、id &lt;= 边界的记录。返回实际搬迁条数。
     * 可被子类覆盖的接缝，用于验证“批失败 + 重试”的幂等性。
     */
    protected long archiveOneBatch(long maxIdInclusive, int batchSize) {
        beforeBatchCommit(maxIdInclusive);
        List<AuditRecord> batch = auditRecordRepository
                .findByIdLessThanEqualOrderByIdAsc(maxIdInclusive, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return 0L;
        }
        for (AuditRecord record : batch) {
            archivedRepository.save(new ArchivedAuditRecord(record));
        }
        auditRecordRepository.deleteAllInBatch(batch);
        return batch.size();
    }

    /**
     * 批提交前钩子：默认无操作。测试可覆盖为在第 N 批抛异常，模拟归档进行中的批次失败。
     */
    protected void beforeBatchCommit(long maxIdInclusive) {
        // no-op
    }
}
