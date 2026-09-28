package com.github.lisama569.settlementledgerjava.domain;

import com.github.lisama569.settlementledgerjava.audit.AuditEventView;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 审计记录：与指令状态变更在同一事务内原子落库。
 * payload 为 JSON，schemaVersion 标识其结构版本，用于结构演进时的确定性补齐。
 */
@Entity
@Table(name = "audit_record",
        indexes = @Index(name = "idx_audit_instruction", columnList = "instruction_id, seq"))
public class AuditRecord implements AuditEventView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instruction_id", nullable = false)
    private Long instructionId;

    /** 同一指令内单调递增的序号，作为重放与分页游标。 */
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

    protected AuditRecord() {
    }

    public AuditRecord(Long instructionId, long seq, SettlementStatus fromStatus, SettlementStatus toStatus,
                       String eventType, int schemaVersion, String payload, Instant createdAt) {
        this.instructionId = instructionId;
        this.seq = seq;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
        this.createdAt = createdAt;
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
