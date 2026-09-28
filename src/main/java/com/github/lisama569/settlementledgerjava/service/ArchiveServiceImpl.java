package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.ArchiveRun;
import com.github.lisama569.settlementledgerjava.domain.ArchivedAuditRecord;
import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import com.github.lisama569.settlementledgerjava.repository.ArchiveRunRepository;
import com.github.lisama569.settlementledgerjava.repository.ArchivedAuditRecordRepository;
import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;
import com.github.lisama569.settlementledgerjava.service.dto.ArchiveResult;
import com.github.lisama569.settlementledgerjava.service.exception.ArchiveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 审计历史归档实现。
 *
 * <p>语义：
 * <ul>
 *   <li>runId 唯一：同一批次（含失败重试）只生效一次；已完成的批次直接回放原结果。</li>
 *   <li>按 cutoffAt 严格早于边界搬移；按热表 id 游标断点续跑，每批独立事务。</li>
 *   <li>「插入归档表 + 删除热表」同事务，任一步失败整体回滚，不漏不重。</li>
 *   <li>归档只搬旧记录（createdAt &lt; cutoff），受理/推进写入的新记录不受影响。</li>
 * </ul>
 */
@Service
public class ArchiveServiceImpl implements ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveServiceImpl.class);
    private static final int BATCH_SIZE = 200;

    private final ArchiveRunRepository archiveRunRepository;
    private final ArchivedAuditRecordRepository archivedAuditRecordRepository;
    private final AuditRecordRepository auditRecordRepository;
    private final TransactionTemplate transactionTemplate;
    private final ArchiveFailureHolder failureHolder;

    /** 进程内串行化归档，避免并发归档批次互相干扰。 */
    private final ReentrantLock archiveLock = new ReentrantLock();

    @org.springframework.beans.factory.annotation.Autowired
    public ArchiveServiceImpl(ArchiveRunRepository archiveRunRepository,
                              ArchivedAuditRecordRepository archivedAuditRecordRepository,
                              AuditRecordRepository auditRecordRepository,
                              PlatformTransactionManager transactionManager) {
        this(archiveRunRepository, archivedAuditRecordRepository, auditRecordRepository,
                transactionManager, new ArchiveFailureHolder());
    }

    /** 测试构造入口：注入批次故障钩子，生产代码始终走无操作的默认构造。 */
    public ArchiveServiceImpl(ArchiveRunRepository archiveRunRepository,
                       ArchivedAuditRecordRepository archivedAuditRecordRepository,
                       AuditRecordRepository auditRecordRepository,
                       PlatformTransactionManager transactionManager,
                       ArchiveFailureHolder failureHolder) {
        this.archiveRunRepository = archiveRunRepository;
        this.archivedAuditRecordRepository = archivedAuditRecordRepository;
        this.auditRecordRepository = auditRecordRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.failureHolder = failureHolder;
    }

    @Override
    public ArchiveResult archive(String runId, Instant cutoffAt) {
        archiveLock.lock();
        try {
            ArchiveRun run = initOrLoadRun(runId, cutoffAt);
            if (run.isCompleted()) {
                log.info("archive already applied: runId={} archivedCount={}", runId, run.getArchivedCount());
                return new ArchiveResult(runId, run.getArchivedCount(), true, true);
            }

            try {
                boolean completed;
                int batchIndex = 0;
                do {
                    completed = processBatch(run, cutoffAt, batchIndex++);
                } while (!completed);
                long total = archiveRunRepository.findByRunId(runId).orElseThrow().getArchivedCount();
                log.info("archive completed: runId={} cutoff={} archivedCount={}", runId, cutoffAt, total);
                return new ArchiveResult(runId, total, true, false);
            } catch (RuntimeException e) {
                log.warn("archive interrupted, resumable: runId={} lastSourceId={} msg={}",
                        runId, run.getLastSourceId(), e.getMessage());
                throw new ArchiveException("Archive run " + runId + " failed; retry with same runId resumes it", e);
            }
        } finally {
            archiveLock.unlock();
        }
    }

    /** 登记归档批次：已存在则复用（断点续跑）；新建时依赖 runId 唯一约束防并发重复。 */
    private ArchiveRun initOrLoadRun(String runId, Instant cutoffAt) {
        return transactionTemplate.execute(tx -> {
            var existing = archiveRunRepository.findByRunId(runId);
            if (existing.isPresent()) {
                ArchiveRun run = existing.get();
                log.info("resume archive: runId={} lastSourceId={} archivedCount={}",
                        runId, run.getLastSourceId(), run.getArchivedCount());
                return run;
            }
            return archiveRunRepository.saveAndFlush(new ArchiveRun(runId, cutoffAt, Instant.now()));
        });
    }

    /** 处理一批：归档插入与热表删除同事务；返回 true 表示没有更多数据。 */
    private boolean processBatch(ArchiveRun run, Instant cutoffAt, int batchIndex) {
        String runId = run.getRunId();
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
            failureHolder.hook().beforeBatch(batchIndex);
            ArchiveRun current = archiveRunRepository.findByRunId(runId).orElseThrow();
            Instant now = Instant.now();

            List<AuditRecord> batch = archivedAuditRecordRepository.findArchivableBatch(
                    cutoffAt, current.getLastSourceId(), PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                current.markCompleted(now);
                archiveRunRepository.saveAndFlush(current);
                return Boolean.TRUE;
            }

            List<Long> sourceIds = new ArrayList<>(batch.size());
            for (AuditRecord r : batch) {
                archivedAuditRecordRepository.save(new ArchivedAuditRecord(
                        r.getId(), runId, r.getInstructionId(), r.getSeq(),
                        r.getFromStatus(), r.getToStatus(), r.getEventType(),
                        r.getSchemaVersion(), r.getPayload(), r.getCreatedAt(), now));
                sourceIds.add(r.getId());
            }
            archivedAuditRecordRepository.flush();
            auditRecordRepository.deleteByIdIn(sourceIds);

            long nextLastId = batch.get(batch.size() - 1).getId();
            current.advance(nextLastId, current.getArchivedCount() + batch.size(), now);
            archiveRunRepository.saveAndFlush(current);
            log.info("archive batch moved: runId={} batchSize={} lastSourceId={} total={}",
                    runId, batch.size(), nextLastId, current.getArchivedCount());
            return Boolean.FALSE;
        }));
    }
}
