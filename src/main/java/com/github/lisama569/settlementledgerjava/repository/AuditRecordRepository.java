package com.github.lisama569.settlementledgerjava.repository;

import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, Long> {

    List<AuditRecord> findByInstructionIdOrderBySeqAsc(Long instructionId);

    /**
     * 键集（游标）分页：按全局自增 id 排序，cursor 为上一页最后一条的 id。
     * 新写入的 id 总是更大，因此持续写入时不会漏项或重项。
     */
    @Query("select a from AuditRecord a where (:cursor is null or a.id > :cursor) order by a.id asc")
    List<AuditRecord> findPageAfter(@Param("cursor") Long cursor, Pageable pageable);
}
