package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ReplayServiceImpl implements ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayServiceImpl.class);

    private final AuditRecordRepository auditRecordRepository;

    public ReplayServiceImpl(AuditRecordRepository auditRecordRepository) {
        this.auditRecordRepository = auditRecordRepository;
    }

    /**
     * 按 seq 顺序重放审计记录，从初始状态（null）逐步推进到最终状态。
     */
    @Override
    @Transactional(readOnly = true)
    public SettlementStatus replayStatus(Long instructionId) {
        List<AuditRecord> records = auditRecordRepository.findByInstructionIdOrderBySeqAsc(instructionId);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("No audit records for instruction: " + instructionId);
        }
        SettlementStatus state = null;
        for (AuditRecord record : records) {
            if (record.getFromStatus() != state) {
                throw new IllegalStateException(
                        "Audit chain broken at seq=" + record.getSeq()
                                + ": expected from=" + state + " actual from=" + record.getFromStatus());
            }
            state = record.getToStatus();
        }
        log.info("replayed: instructionId={} events={} finalStatus={}", instructionId, records.size(), state);
        return state;
    }
}
