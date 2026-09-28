package com.github.lisama569.settlementledgerjava.repository;

import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SettlementInstructionRepository extends JpaRepository<SettlementInstruction, Long> {

    Optional<SettlementInstruction> findByIdempotencyKey(String idempotencyKey);

    /**
     * 对账导出用的指令 id 键集分页：可叠加受理时间窗口。
     * cursor 为上一页最后一个指令 id（取严格大于），新受理的指令 id 更大，不漏不重。
     */
    @Query("""
            select i.id from SettlementInstruction i
            where (:cursor is null or i.id > :cursor)
              and (:fromTime is null or i.createdAt >= :fromTime)
              and (:toTime is null or i.createdAt < :toTime)
            order by i.id asc
            """)
    List<Long> findIdsForExport(@Param("cursor") Long cursor,
                                @Param("fromTime") Instant fromTime,
                                @Param("toTime") Instant toTime,
                                Pageable pageable);
}
