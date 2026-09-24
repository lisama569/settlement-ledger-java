package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.domain.SubmitCommand;
import com.github.lisama569.settlementledgerjava.domain.SubmitResult;
import com.github.lisama569.settlementledgerjava.domain.TransitionCommand;
import com.github.lisama569.settlementledgerjava.error.IdempotencyConflictException;
import com.github.lisama569.settlementledgerjava.error.OptimisticLockConflictException;
import com.github.lisama569.settlementledgerjava.error.SettlementException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class IdempotencyAndTransitionTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAndTransitionTest.class);

    @Autowired
    private SettlementService settlementService;

    @Test
    void duplicate_idempotency_key_returns_same_result_flagged() {
        SubmitCommand first = TestFixtures.submit("idem-dup-1", "INS-DUP-1");
        SubmitResult r1 = settlementService.submit(first);
        SubmitResult r2 = settlementService.submit(first);
        SubmitResult r3 = settlementService.submit(first);

        log.info("idempotencyKey=idem-dup-1 firstVersion={} dup2Version={} dup3Version={} duplicated flags={},{}",
                r1.version(), r2.version(), r3.version(), r2.duplicated(), r3.duplicated());

        assertFalse(r1.duplicated());
        assertTrue(r2.duplicated());
        assertTrue(r3.duplicated());
        assertEquals(r1.instructionId(), r2.instructionId());
        assertEquals(r1.version(), r2.version());
        assertEquals(r1.status(), r2.status());
    }

    @Test
    void same_key_different_payload_is_distinguishable_conflict() {
        settlementService.submit(TestFixtures.submit("idem-dup-2", "INS-DUP-2"));
        SubmitCommand tampered = new SubmitCommand("idem-dup-2", "INS-DUP-2",
                "DEBT-9999", "CRED-0001", new BigDecimal("999.00"), "USD", "WEB", "tester");

        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class,
                () -> settlementService.submit(tampered));
        log.warn("idempotencyKey=idem-dup-2 conflict errorCode={} existingInstructionId={} existingStatus={}",
                ex.getErrorCode(), ex.getExistingInstructionId(), ex.getExistingStatus());
        assertEquals("INS-DUP-2", ex.getExistingInstructionId());
        // 被篡改的请求不得生效：金额与账户保持首次值
        InstructionSnapshot snapshot = settlementService.get("INS-DUP-2").orElseThrow();
        assertEquals(0, new BigDecimal("100.50").compareTo(snapshot.amount()));
        assertEquals("DEBT-0001", snapshot.debtorAccount());
    }

    @Test
    void full_lifecycle_versions_increment_and_stale_version_conflicts() {
        String key = "idem-life-1";
        SubmitResult accepted = settlementService.submit(TestFixtures.submit(key, "INS-LIFE-1"));
        log.info("lifecycle idempotencyKey={} status=ACCEPTED version={}", key, accepted.version());

        InstructionSnapshot processing = settlementService.transition(new TransitionCommand(
                "INS-LIFE-1", accepted.version(), SettlementStatus.PROCESSING, null));
        log.info("lifecycle idempotencyKey={} status=PROCESSING version={}", key, processing.version());
        assertEquals(2L, processing.version());

        // 用过时版本推进：必须以 OPTIMISTIC_LOCK_CONFLICT 区分
        OptimisticLockConflictException conflict = assertThrows(OptimisticLockConflictException.class,
                () -> settlementService.transition(new TransitionCommand(
                        "INS-LIFE-1", accepted.version(), SettlementStatus.SETTLED, null)));
        log.info("lifecycle stale push expectedVersion={} actualVersion={} errorCode={}",
                conflict.getExpectedVersion(), conflict.getActualVersion(), conflict.getErrorCode());
        assertEquals(1L, conflict.getExpectedVersion());
        assertEquals(2L, conflict.getActualVersion());

        InstructionSnapshot settled = settlementService.transition(new TransitionCommand(
                "INS-LIFE-1", processing.version(), SettlementStatus.SETTLED, null));
        log.info("lifecycle idempotencyKey={} status=SETTLED version={}", key, settled.version());
        assertEquals(3L, settled.version());

        // 终态再推进：非法流转，与乐观冲突不同的错误码
        SettlementException illegal = assertThrows(SettlementException.class,
                () -> settlementService.transition(new TransitionCommand(
                        "INS-LIFE-1", settled.version(), SettlementStatus.PROCESSING, null)));
        assertEquals(com.github.lisama569.settlementledgerjava.error.ErrorCode.ILLEGAL_STATUS_TRANSITION,
                illegal.getErrorCode());
        log.info("lifecycle terminal push rejected errorCode={} snapshotVersion={}",
                illegal.getErrorCode(), settled.version());
    }

    @Test
    void rejection_requires_reason_and_records_it() {
        SubmitResult accepted = settlementService.submit(TestFixtures.submit("idem-rej-1", "INS-REJ-1"));
        SettlementException noReason = assertThrows(SettlementException.class,
                () -> settlementService.transition(new TransitionCommand(
                        "INS-REJ-1", accepted.version(), SettlementStatus.REJECTED, null)));
        assertEquals(com.github.lisama569.settlementledgerjava.error.ErrorCode.VALIDATION_FAILED,
                noReason.getErrorCode());

        InstructionSnapshot rejected = settlementService.transition(new TransitionCommand(
                "INS-REJ-1", accepted.version(), SettlementStatus.REJECTED, "account frozen"));
        log.info("rejection idempotencyKey=idem-rej-1 version={} status={} reason={}",
                rejected.version(), rejected.status(), rejected.rejectionReason());
        assertEquals("account frozen", rejected.rejectionReason());
    }

    @Test
    void duplicate_submit_after_lifecycle_returns_latest_state() {
        String key = "idem-after-1";
        SubmitResult accepted = settlementService.submit(TestFixtures.submit(key, "INS-AFTER-1"));
        settlementService.transition(new TransitionCommand(
                "INS-AFTER-1", accepted.version(), SettlementStatus.PROCESSING, null));

        SubmitResult duplicate = settlementService.submit(TestFixtures.submit(key, "INS-AFTER-1"));
        log.info("idempotencyKey={} late-duplicate status={} version={} duplicated={}",
                key, duplicate.status(), duplicate.version(), duplicate.duplicated());
        assertTrue(duplicate.duplicated());
        assertEquals(SettlementStatus.PROCESSING, duplicate.status());
        assertEquals(2L, duplicate.version());
    }
}
