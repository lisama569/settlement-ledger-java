package com.github.lisama569.settlementledgerjava.repository;

import com.github.lisama569.settlementledgerjava.domain.ArchivedAuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ArchivedAuditRecordRepository extends JpaRepository<ArchivedAuditRecord, Long> {

    List<ArchivedAuditRecord> findByInstructionIdOrderBySeqAsc(Long instructionId);

    /**
     * 归档批次断点续跑：按 sourceId 游标取一批 cutoffAt 之前、尚未归档的热表记录。
     */
    @Query("""
            select a from AuditRecord a
            where a.createdAt < :cutoff
              and a.id > :afterId
              and not exists (select 1 from ArchivedAuditRecord x where x.sourceId = a.id)
            order by a.id asc
            """)
    List<com.github.lisama569.settlementledgerjava.domain.AuditRecord> findArchivableBatch(
            @Param("cutoff") Instant cutoff,
            @Param("afterId") long afterId,
            org.springframework.data.domain.Pageable pageable);

    boolean existsBySourceId(Long sourceId);
}
