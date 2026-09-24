package com.github.lisama569.settlementledgerjava.repository;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<IdempotentRecord> findByKey(String idempotencyKey) {
        return jdbcTemplate.query(
                        """
                        SELECT idempotency_key, instruction_id, request_fingerprint, status
                          FROM idempotency_records
                         WHERE idempotency_key = ?
                        """,
                        (rs, rowNum) -> new IdempotentRecord(
                                rs.getString("idempotency_key"),
                                rs.getString("instruction_id"),
                                rs.getString("request_fingerprint"),
                                SettlementStatus.valueOf(rs.getString("status"))),
                        idempotencyKey)
                .stream().findFirst();
    }

    /**
     * 以唯一主键竞争实现并发去重：同一幂等键并发时只有一个插入成功。
     * @return true 插入成功；false 键已存在
     */
    public boolean insertIfAbsent(String idempotencyKey, String instructionId,
                                  String fingerprint, SettlementStatus status) {
        Instant now = Instant.now();
        try {
            jdbcTemplate.update("""
                            INSERT INTO idempotency_records
                                (idempotency_key, instruction_id, request_fingerprint, status, created_at, updated_at)
                            VALUES (?,?,?,?,?,?)
                            """,
                    idempotencyKey, instructionId, fingerprint, status.name(),
                    Timestamp.from(now), Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void updateStatus(String idempotencyKey, SettlementStatus status) {
        jdbcTemplate.update(
                "UPDATE idempotency_records SET status = ?, updated_at = ? WHERE idempotency_key = ?",
                status.name(), Timestamp.from(Instant.now()), idempotencyKey);
    }

    /**
     * 指令状态推进后联动更新其幂等记录中缓存的最新状态。
     */
    public void updateStatusByInstruction(String instructionId, SettlementStatus status) {
        jdbcTemplate.update("""
                UPDATE idempotency_records
                   SET status = ?, updated_at = ?
                 WHERE instruction_id = ?
                """, status.name(), Timestamp.from(Instant.now()), instructionId);
    }

    public record IdempotentRecord(String idempotencyKey, String instructionId,
                                   String requestFingerprint, SettlementStatus status) {
    }
}
