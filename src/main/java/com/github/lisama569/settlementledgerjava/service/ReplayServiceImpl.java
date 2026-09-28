package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.audit.AuditEventView;
import com.github.lisama569.settlementledgerjava.audit.AuditHistoryReader;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ReplayServiceImpl implements ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayServiceImpl.class);

    private final AuditHistoryReader auditHistoryReader;

    public ReplayServiceImpl(AuditHistoryReader auditHistoryReader) {
        this.auditHistoryReader = auditHistoryReader;
    }

    /**
     * 按 seq 顺序重放审计记录（在线 + 归档合并后的完整链条），从初始状态（null）
     * 逐步推进到最终状态，并校验链条前后状态衔接。
     */
    @Override
    @Transactional(readOnly = true)
    public SettlementStatus replayStatus(Long instructionId) {
        List<AuditEventView> records = auditHistoryReader.loadChain(instructionId);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("No audit records for instruction: " + instructionId);
        }
        SettlementStatus state = null;
        for (AuditEventView record : records) {
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
