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
     * 批量导出分页：按 id 键集游标，可选受理时间窗口过滤。
     */
    @Query("""
            select i from SettlementInstruction i
            where (:afterId is null or i.id > :afterId)
              and (:from is null or i.createdAt >= :from)
              and (:to is null or i.createdAt < :to)
            order by i.id asc
            """)
    List<SettlementInstruction> findExportPage(@Param("afterId") Long afterId,
                                               @Param("from") Instant from,
                                               @Param("to") Instant to,
                                               Pageable pageable);
}
