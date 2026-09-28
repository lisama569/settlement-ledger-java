package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.audit.AuditHistoryEntry;
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

    private final AuditHistoryReader historyReader;

    public ReplayServiceImpl(AuditHistoryReader historyReader) {
        this.historyReader = historyReader;
    }

    /**
     * 按 seq 顺序重放审计记录（热表 + 归档表合并视图），从初始状态（null）逐步推进。
     * 归档前后读取到的序列一致，因此重放结果也一致。
     */
    @Override
    @Transactional(readOnly = true)
    public SettlementStatus replayStatus(Long instructionId) {
        List<AuditHistoryEntry> records = historyReader.historyOf(instructionId);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("No audit records for instruction: " + instructionId);
        }
        SettlementStatus state = null;
        for (AuditHistoryEntry record : records) {
            if (record.fromStatus() != state) {
                throw new IllegalStateException(
                        "Audit chain broken at seq=" + record.seq()
                                + ": expected from=" + state + " actual from=" + record.fromStatus());
            }
            state = record.toStatus();
        }
        log.info("replayed: instructionId={} events={} finalStatus={}", instructionId, records.size(), state);
        return state;
    }
}
