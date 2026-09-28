package com.github.lisama569.settlementledgerjava.audit;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

import java.time.Instant;

/**
 * 审计历史统一视图：热表与归档表记录归一化后的条目。
 */
public record AuditHistoryEntry(Long sourceId,
                                Long instructionId,
                                long seq,
                                SettlementStatus fromStatus,
                                SettlementStatus toStatus,
                                String eventType,
                                int schemaVersion,
                                String payload,
                                Instant createdAt) {
}
