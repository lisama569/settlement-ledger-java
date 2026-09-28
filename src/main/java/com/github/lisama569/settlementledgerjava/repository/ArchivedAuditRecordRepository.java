package com.github.lisama569.settlementledgerjava.repository;

import com.github.lisama569.settlementledgerjava.domain.ArchivedAuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArchivedAuditRecordRepository extends JpaRepository<ArchivedAuditRecord, Long> {

    /** 归档表按指令读取（与在线表读取方法对齐，便于合并）。 */
    List<ArchivedAuditRecord> findByInstructionIdOrderBySeqAsc(Long instructionId);
}
