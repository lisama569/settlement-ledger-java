package com.github.lisama569.settlementledgerjava.audit;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

import java.time.Instant;

/**
 * 审计事件的统一只读视图：在线表 {@code AuditRecord} 与归档表
 * {@code ArchivedAuditRecord} 都实现该接口，使重放/重建代码无需关心数据存放位置。
 */
public interface AuditEventView {

    Long getId();

    Long getInstructionId();

    long getSeq();

    SettlementStatus getFromStatus();

    SettlementStatus getToStatus();

    String getEventType();

    int getSchemaVersion();

    String getPayload();

    Instant getCreatedAt();
}
