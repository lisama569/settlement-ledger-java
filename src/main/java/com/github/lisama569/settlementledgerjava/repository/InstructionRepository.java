package com.github.lisama569.settlementledgerjava.repository;

import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class InstructionRepository {

    private static final String COLUMNS = """
            instruction_id, debtor_account, creditor_account, amount, currency, channel,
            status, version, submitted_by, rejection_reason, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public InstructionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<SettlementInstruction> ROW_MAPPER = (rs, rowNum) -> new SettlementInstruction(
            rs.getString("instruction_id"),
            rs.getString("debtor_account"),
            rs.getString("creditor_account"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("channel"),
            SettlementStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            rs.getString("submitted_by"),
            rs.getString("rejection_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    public void insert(SettlementInstruction instruction) {
        jdbcTemplate.update("""
                        INSERT INTO settlement_instructions (
                            instruction_id, debtor_account, creditor_account, amount, currency, channel,
                            status, version, submitted_by, rejection_reason, created_at, updated_at
                        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                instruction.instructionId(),
                instruction.debtorAccount(),
                instruction.creditorAccount(),
                instruction.amount(),
                instruction.currency(),
                instruction.channel(),
                instruction.status().name(),
                instruction.version(),
                instruction.submittedBy(),
                instruction.rejectionReason(),
                Timestamp.from(instruction.createdAt()),
                Timestamp.from(instruction.updatedAt()));
    }

    public Optional<SettlementInstruction> findById(String instructionId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM settlement_instructions WHERE instruction_id = ?",
                        ROW_MAPPER, instructionId)
                .stream().findFirst();
    }

    /**
     * 乐观更新：仅当 version = expectedVersion 时生效，版本号自增。
     * @param expectedVersion 调用方读到的基准版本（WHERE 条件）
     * @return 受影响行数，0 表示版本冲突
     */
    public int compareAndUpdateStatus(SettlementInstruction next, long expectedVersion) {
        return jdbcTemplate.update("""
                        UPDATE settlement_instructions
                           SET status = ?, version = ? ,
                               rejection_reason = ?, updated_at = ?
                         WHERE instruction_id = ? AND version = ?
                        """,
                next.status().name(),
                next.version(),
                next.rejectionReason(),
                Timestamp.from(next.updatedAt()),
                next.instructionId(),
                expectedVersion);
    }
}
