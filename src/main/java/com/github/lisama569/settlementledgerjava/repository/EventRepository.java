package com.github.lisama569.settlementledgerjava.repository;

import com.github.lisama569.settlementledgerjava.domain.EventType;
import com.github.lisama569.settlementledgerjava.domain.SettlementEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class EventRepository {

    private static final RowMapper<SettlementEvent> ROW_MAPPER = (rs, rowNum) -> new SettlementEvent(
            rs.getLong("seq_no"),
            rs.getString("instruction_id"),
            EventType.valueOf(rs.getString("event_type")),
            rs.getInt("payload_version"),
            rs.getString("payload"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public EventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加事件，seq_no 由 identity 列单调分配并回填。
     */
    public SettlementEvent append(SettlementEvent event) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO settlement_events
                        (instruction_id, event_type, payload_version, payload, occurred_at)
                    VALUES (?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, event.instructionId());
            ps.setString(2, event.eventType().name());
            ps.setInt(3, event.payloadVersion());
            ps.setString(4, event.payload());
            ps.setTimestamp(5, Timestamp.from(event.occurredAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("event seq_no was not generated");
        }
        return new SettlementEvent(key.longValue(), event.instructionId(), event.eventType(),
                event.payloadVersion(), event.payload(), event.occurredAt());
    }

    public List<SettlementEvent> findByInstruction(String instructionId) {
        return jdbcTemplate.query("""
                SELECT seq_no, instruction_id, event_type, payload_version, payload, occurred_at
                  FROM settlement_events
                 WHERE instruction_id = ?
                 ORDER BY seq_no ASC
                """, ROW_MAPPER, instructionId);
    }

    /**
     * 键集分页：只取 seq_no 严格大于游标的记录。持续写入时游标不会回卷，
     * 因而不会漏项（新写入 seq_no 更大，下一页自然出现），也不会重项。
     */
    public List<SettlementEvent> findPage(long afterSeq, int limit) {
        return jdbcTemplate.query("""
                SELECT seq_no, instruction_id, event_type, payload_version, payload, occurred_at
                  FROM settlement_events
                 WHERE seq_no > ?
                 ORDER BY seq_no ASC
                 FETCH FIRST ? ROWS ONLY
                """, ROW_MAPPER, afterSeq, limit);
    }

    public Optional<SettlementEvent> findLastByInstruction(String instructionId) {
        return jdbcTemplate.query("""
                SELECT seq_no, instruction_id, event_type, payload_version, payload, occurred_at
                  FROM settlement_events
                 WHERE instruction_id = ?
                 ORDER BY seq_no DESC
                 FETCH FIRST 1 ROW ONLY
                """, ROW_MAPPER, instructionId).stream().findFirst();
    }

    public long maxSeqNo() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(seq_no), 0) FROM settlement_events",
                Long.class);
        return max == null ? 0L : max;
    }
}
