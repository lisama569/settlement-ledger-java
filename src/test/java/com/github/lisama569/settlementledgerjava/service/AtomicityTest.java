package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.domain.TransitionCommand;
import com.github.lisama569.settlementledgerjava.repository.EventRepository;
import com.github.lisama569.settlementledgerjava.repository.IdempotencyRepository;
import com.github.lisama569.settlementledgerjava.repository.InstructionRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原子性验证：指令状态推进要求"状态行 + 审计事件"同时存在或同时不存在。
 * 通过在同一事务内追加第二条事件成功后人为制造异常（CAS 之后的步骤失败），
 * 验证整个事务回滚，不留下半条数据。
 */
@SpringBootTest
class AtomicityTest {

    private static final Logger log = LoggerFactory.getLogger(AtomicityTest.class);

    @Autowired private SettlementService settlementService;
    @Autowired private InstructionRepository instructionRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private IdempotencyRepository idempotencyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    @Test
    void failure_after_event_append_leaves_no_partial_data() {
        settlementService.submit(TestFixtures.submit("idem-atom-1", "INS-ATOM-1"));

        long eventsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_events WHERE instruction_id='INS-ATOM-1'", Long.class);
        SettlementInstruction before = instructionRepository.findById("INS-ATOM-1").orElseThrow();
        log.info("atomicity-before idempotencyKey=idem-atom-1 version={} status={} events={}",
                before.version(), before.status(), eventsBefore);

        // 构造：CAS 成功、事件追加成功、但在提交前再制造一次冲突写（重复主键）使事务回滚
        RuntimeException boom = new RuntimeException("simulated post-append failure");
        try {
            new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(s -> {
                int affected = instructionRepository.compareAndUpdateStatus(new SettlementInstruction(
                        before.instructionId(), before.debtorAccount(), before.creditorAccount(),
                        before.amount(), before.currency(), before.channel(),
                        SettlementStatus.PROCESSING, before.version() + 1, before.submittedBy(),
                        null, before.createdAt(), java.time.Instant.now()), before.version());
                assertEquals(1, affected);
                eventRepository.append(new com.github.lisama569.settlementledgerjava.domain.SettlementEvent(
                        0L, "INS-ATOM-1", com.github.lisama569.settlementledgerjava.domain.EventType.STATUS_CHANGED,
                        EventPayloads.CURRENT_PAYLOAD_VERSION,
                        EventPayloads.encode(com.github.lisama569.settlementledgerjava.domain.EventPayload
                                .statusChanged("INS-ATOM-1", SettlementStatus.ACCEPTED,
                                        SettlementStatus.PROCESSING, null)),
                        java.time.Instant.now()));
                // 人为失败：必须连带回滚上面的状态更新与事件
                throw boom;
            });
        } catch (RuntimeException e) {
            assertTrue(e == boom);
        }

        SettlementInstruction after = instructionRepository.findById("INS-ATOM-1").orElseThrow();
        long eventsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_events WHERE instruction_id='INS-ATOM-1'", Long.class);
        log.info("atomicity-after idempotencyKey=idem-atom-1 version={} status={} events={}",
                after.version(), after.status(), eventsAfter);

        assertEquals(before.version(), after.version(), "status update must be rolled back");
        assertEquals(SettlementStatus.ACCEPTED, after.status());
        assertEquals(eventsBefore, eventsAfter, "event append must be rolled back");
    }

    @Test
    void invalid_submit_leaves_nothing() {
        try {
            settlementService.submit(new com.github.lisama569.settlementledgerjava.domain.SubmitCommand(
                    "idem-atom-bad", "INS-ATOM-BAD", "D", "C",
                    java.math.BigDecimal.ZERO, "CNY", "WEB", "t"));
        } catch (RuntimeException ignored) {
        }
        Integer instructions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_instructions WHERE instruction_id='INS-ATOM-BAD'", Integer.class);
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_events WHERE instruction_id='INS-ATOM-BAD'", Integer.class);
        Integer idem = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key='idem-atom-bad'", Integer.class);
        log.info("atomicity-invalid-submit idempotencyKey=idem-atom-bad instructions={} events={} idempotency={}",
                instructions, events, idem);
        assertEquals(0, instructions);
        assertEquals(0, events);
        assertEquals(0, idem);
    }
}
