package com.github.lisama569.settlementledgerjava.repository;

import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementInstructionRepository extends JpaRepository<SettlementInstruction, Long> {

    Optional<SettlementInstruction> findByIdempotencyKey(String idempotencyKey);
}
