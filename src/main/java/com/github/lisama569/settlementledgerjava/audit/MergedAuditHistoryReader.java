package com.github.lisama569.settlementledgerjava.audit;

import com.github.lisama569.settlementledgerjava.domain.ArchivedAuditRecord;
import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import com.github.lisama569.settlementledgerjava.repository.ArchivedAuditRecordRepository;
import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 合并热表与归档表的审计历史读取。按 (seq, sourceId) 稳定排序，
 * 保证同一份数据（无论是否已归档）读出的序列完全一致。
 */
@Component
public class MergedAuditHistoryReader implements AuditHistoryReader {

    private final AuditRecordRepository auditRecordRepository;
    private final ArchivedAuditRecordRepository archivedAuditRecordRepository;

    public MergedAuditHistoryReader(AuditRecordRepository auditRecordRepository,
                                    ArchivedAuditRecordRepository archivedAuditRecordRepository) {
        this.auditRecordRepository = auditRecordRepository;
        this.archivedAuditRecordRepository = archivedAuditRecordRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditHistoryEntry> historyOf(Long instructionId) {
        List<AuditHistoryEntry> entries = new ArrayList<>();
        for (AuditRecord r : auditRecordRepository.findByInstructionIdOrderBySeqAsc(instructionId)) {
            entries.add(new AuditHistoryEntry(r.getId(), r.getInstructionId(), r.getSeq(),
                    r.getFromStatus(), r.getToStatus(), r.getEventType(),
                    r.getSchemaVersion(), r.getPayload(), r.getCreatedAt()));
        }
        for (ArchivedAuditRecord r : archivedAuditRecordRepository.findByInstructionIdOrderBySeqAsc(instructionId)) {
            entries.add(new AuditHistoryEntry(r.getSourceId(), r.getInstructionId(), r.getSeq(),
                    r.getFromStatus(), r.getToStatus(), r.getEventType(),
                    r.getSchemaVersion(), r.getPayload(), r.getCreatedAt()));
        }
        entries.sort(Comparator.comparingLong(AuditHistoryEntry::seq)
                .thenComparing(e -> e.sourceId() == null ? 0L : e.sourceId()));
        return entries;
    }

    @Override
    @Transactional(readOnly = true)
    public long maxSeqOf(Long instructionId) {
        long hot = auditRecordRepository.findByInstructionIdOrderBySeqAsc(instructionId).stream()
                .mapToLong(AuditRecord::getSeq).max().orElse(0L);
        long archived = archivedAuditRecordRepository.findByInstructionIdOrderBySeqAsc(instructionId).stream()
                .mapToLong(ArchivedAuditRecord::getSeq).max().orElse(0L);
        return Math.max(hot, archived);
    }
}
