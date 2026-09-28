package com.github.lisama569.settlementledgerjava.repository;

import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, Long> {

    List<AuditRecord> findByInstructionIdOrderBySeqAsc(Long instructionId);

    /**
     * 归档候选：创建时间早于截止点的记录 id，按 id 升序，分批搬迁。
     */
    @Query("select a.id from AuditRecord a where a.createdAt < :cutoff order by a.id asc")
    List<Long> findIdsByCreatedAtBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * 归档搬迁：取在线表中 id 不超过边界的一批记录（按 id 升序）。
     */
    List<AuditRecord> findByIdLessThanEqualOrderByIdAsc(Long maxId, Pageable pageable);

    /**
     * 键集（游标）分页：按全局自增 id 排序，cursor 为上一页最后一条的 id。
     * 新写入的 id 总是更大，因此持续写入时不会漏项或重项。
     */
    @Query("select a from AuditRecord a where (:cursor is null or a.id > :cursor) order by a.id asc")
    List<AuditRecord> findPageAfter(@Param("cursor") Long cursor, Pageable pageable);
}
