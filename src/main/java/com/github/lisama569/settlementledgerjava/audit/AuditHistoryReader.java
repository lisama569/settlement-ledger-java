package com.github.lisama569.settlementledgerjava.audit;

import com.github.lisama569.settlementledgerjava.domain.ArchivedAuditRecord;
import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import com.github.lisama569.settlementledgerjava.repository.ArchivedAuditRecordRepository;
import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;
import com.github.lisama569.settlementledgerjava.service.exception.AuditChainBrokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 合并在线表与归档表，按 seq 升序返回一条指令的完整审计链条。
 * 归档对调用方透明：无论记录是否已搬迁、是否部分搬迁，拿到的都是同一条完整链条。
 * 两张表主键不重叠（归档保留原始 id），不会出现重复事件。
 */
@Component
public class AuditHistoryReader {

    private static final Logger log = LoggerFactory.getLogger(AuditHistoryReader.class);

    private final AuditRecordRepository auditRecordRepository;
    private final ArchivedAuditRecordRepository archivedRepository;

    public AuditHistoryReader(AuditRecordRepository auditRecordRepository,
                              ArchivedAuditRecordRepository archivedRepository) {
        this.auditRecordRepository = auditRecordRepository;
        this.archivedRepository = archivedRepository;
    }

    /**
     * 计算下一个可用 seq：合并在线与归档表，取该指令已有最大 seq + 1。
     * 归档后继续推进时，新事件的 seq 必须接在已归档历史之后，不能从在线表重新计数。
     */
    public long nextSeq(Long instructionId) {
        long maxLive = auditRecordRepository.findByInstructionIdOrderBySeqAsc(instructionId)
                .stream().mapToLong(AuditRecord::getSeq).max().orElse(0L);
        long maxArchived = archivedRepository.findByInstructionIdOrderBySeqAsc(instructionId)
                .stream().mapToLong(ArchivedAuditRecord::getSeq).max().orElse(0L);
        return Math.max(maxLive, maxArchived) + 1L;
    }

    public List<AuditEventView> loadChain(Long instructionId) {
        List<AuditRecord> live = auditRecordRepository.findByInstructionIdOrderBySeqAsc(instructionId);
        List<ArchivedAuditRecord> archived = archivedRepository.findByInstructionIdOrderBySeqAsc(instructionId);

        List<AuditEventView> merged = new ArrayList<>(live.size() + archived.size());
        merged.addAll(live);
        merged.addAll(archived);
        merged.sort(Comparator.comparingLong(AuditEventView::getSeq)
                .thenComparing(AuditEventView::getId));

        long expectedSeq = 1L;
        for (AuditEventView event : merged) {
            if (event.getSeq() != expectedSeq) {
                throw new AuditChainBrokenException(instructionId,
                        "Audit chain broken: expected seq=" + expectedSeq + " but found seq=" + event.getSeq()
                                + " (liveEvents=" + live.size() + ", archivedEvents=" + archived.size() + ")");
            }
            expectedSeq++;
        }

        log.debug("loaded chain: instructionId={} live={} archived={} total={}",
                instructionId, live.size(), archived.size(), merged.size());
        return merged;
    }
}
