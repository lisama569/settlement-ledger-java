package com.github.lisama569.settlementledgerjava.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 已归档的审计记录：字段与 {@link AuditRecord} 一一对应，额外记录所属归档批次 runId。
 * 归档后重建快照从热表 + 归档表的合并视图读取，结果与归档前完全一致。
 */
@Entity
@Table(name = "archived_audit_record",
        indexes = @Index(name = "idx_archived_instruction", columnList = "instruction_id, seq"),
        uniqueConstraints = @UniqueConstraint(name = "uk_archived_run_source", columnNames = {"run_id", "source_id"}))
public class ArchivedAuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

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

    @Column(name = "archived_at", nullable = false)
    private Instant archivedAt;

    protected ArchivedAuditRecord() {
    }

    public ArchivedAuditRecord(Long sourceId, String runId, Long instructionId, long seq,
                               SettlementStatus fromStatus, SettlementStatus toStatus,
                               String eventType, int schemaVersion, String payload,
                               Instant createdAt, Instant archivedAt) {
        this.sourceId = sourceId;
        this.runId = runId;
        this.instructionId = instructionId;
        this.seq = seq;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
        this.createdAt = createdAt;
        this.archivedAt = archivedAt;
    }

    public Long getId() { return id; }
    public Long getSourceId() { return sourceId; }
    public String getRunId() { return runId; }
    public Long getInstructionId() { return instructionId; }
    public long getSeq() { return seq; }
    public SettlementStatus getFromStatus() { return fromStatus; }
    public SettlementStatus getToStatus() { return toStatus; }
    public String getEventType() { return eventType; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getArchivedAt() { return archivedAt; }
}
