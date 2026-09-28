package com.github.lisama569.settlementledgerjava.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 归档批次状态：runId 唯一，保证同一批次（含失败重试）只生效一次。
 */
@Entity
@Table(name = "archive_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_archive_run_id", columnNames = "run_id"))
public class ArchiveRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    /** 归档边界：createdAt 严格早于该时刻的审计记录会被归档。 */
    @Column(name = "cutoff_at", nullable = false)
    private Instant cutoffAt;

    /** 本次已处理到的热表审计记录 id（断点续跑游标）。 */
    @Column(name = "last_source_id", nullable = false)
    private long lastSourceId;

    @Column(name = "archived_count", nullable = false)
    private long archivedCount;

    @Column(name = "completed", nullable = false)
    private boolean completed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ArchiveRun() {
    }

    public ArchiveRun(String runId, Instant cutoffAt, Instant now) {
        this.runId = runId;
        this.cutoffAt = cutoffAt;
        this.lastSourceId = 0L;
        this.archivedCount = 0L;
        this.completed = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public Instant getCutoffAt() { return cutoffAt; }
    public long getLastSourceId() { return lastSourceId; }
    public long getArchivedCount() { return archivedCount; }
    public boolean isCompleted() { return completed; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void advance(long lastSourceId, long archivedCount, Instant now) {
        this.lastSourceId = lastSourceId;
        this.archivedCount = archivedCount;
        this.updatedAt = now;
    }

    public void markCompleted(Instant now) {
        this.completed = true;
        this.updatedAt = now;
    }
}
