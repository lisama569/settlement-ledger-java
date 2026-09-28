package com.github.lisama569.settlementledgerjava.tests.support;

import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.repository.SettlementInstructionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 造“结构变更之前落库”的老指令：当前态实体 + 指定 schemaVersion/原文 payload 的
 * SUBMITTED 审计（直接写库，绕过新写入路径），用于重建/导出/归档测试。
 */
public final class LegacyAuditFixtures {

    private LegacyAuditFixtures() {
    }

    public static Long seedSubmitted(JdbcTemplate jdbc,
                                     SettlementInstructionRepository instructions,
                                     String key, int schemaVersion, String payload) {
        Instant now = Instant.now();
        SettlementInstruction instruction = new SettlementInstruction(
                key, new BigDecimal("9.99"), "CNY", "legacy", "legacy", now);
        instruction = instructions.saveAndFlush(instruction);
        Long id = instruction.getId();
        jdbc.update("insert into audit_record (instruction_id, seq, from_status, to_status, event_type, "
                        + "schema_version, payload, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, 1L, null, SettlementStatus.RECEIVED.name(), "SUBMITTED",
                schemaVersion, payload, java.sql.Timestamp.from(now));
        return id;
    }

    /** 直接造一条链条断裂（seq 从 2 开始）的指令审计，模拟历史链条不连续。 */
    public static Long seedBrokenChain(JdbcTemplate jdbc,
                                       SettlementInstructionRepository instructions,
                                       String key) {
        Instant now = Instant.now();
        SettlementInstruction instruction = new SettlementInstruction(
                key, new BigDecimal("3.00"), "CNY", "x", "y", now);
        instruction = instructions.saveAndFlush(instruction);
        Long id = instruction.getId();
        jdbc.update("insert into audit_record (instruction_id, seq, from_status, to_status, event_type, "
                        + "schema_version, payload, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, 2L, SettlementStatus.RECEIVED.name(), SettlementStatus.VALIDATED.name(), "STATUS_CHANGED",
                2, "{\"idempotencyKey\":\"" + key + "\"}", java.sql.Timestamp.from(now));
        return id;
    }
}
