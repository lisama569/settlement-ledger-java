package com.github.lisama569.settlementledgerjava.domain;

import com.github.lisama569.settlementledgerjava.audit.AuditEventView;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 已归档的审计记录：结构与 {@link AuditRecord} 一致，主键保留原始审计记录 id，
 * 使归档天然幂等（重试不会重复插入），重建时与在线记录按 id 合并即可得到完整链条。
 */
@Entity
@Table(name = "archived_audit_record",
        indexes = @Index(name = "idx_archived_audit_instruction", columnList = "instruction_id, seq"))
public class ArchivedAuditRecord implements AuditEventView {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "instruction_id", nullable = false)
    private Long instructionId;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private SettlementStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private SettlementStatus toStatus;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ArchivedAuditRecord() {
    }

    public ArchivedAuditRecord(AuditRecord r) {
        this.id = r.getId();
        this.instructionId = r.getInstructionId();
        this.seq = r.getSeq();
        this.fromStatus = r.getFromStatus();
        this.toStatus = r.getToStatus();
        this.eventType = r.getEventType();
        this.schemaVersion = r.getSchemaVersion();
        this.payload = r.getPayload();
        this.createdAt = r.getCreatedAt();
    }

    public Long getId() { return id; }
    public Long getInstructionId() { return instructionId; }
    public long getSeq() { return seq; }
    public SettlementStatus getFromStatus() { return fromStatus; }
    public SettlementStatus getToStatus() { return toStatus; }
    public String getEventType() { return eventType; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
