package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.domain.SubmitResult;
import com.github.lisama569.settlementledgerjava.domain.TransitionCommand;
import com.github.lisama569.settlementledgerjava.error.ErrorCode;
import com.github.lisama569.settlementledgerjava.error.OptimisticLockConflictException;
import com.github.lisama569.settlementledgerjava.error.SettlementException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SettlementConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(SettlementConcurrencyTest.class);

    @Autowired
    private SettlementService settlementService;

    @Test
    void concurrent_transitions_same_base_version_exactly_one_wins() throws Exception {
        SubmitResult accepted = settlementService.submit(TestFixtures.submit("idem-conc-trans", "INS-CONC-1"));
        log.info("SETUP idempotencyKey=idem-conc-trans instructionId=INS-CONC-1 version={} status={}",
                accepted.version(), accepted.status());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicReference<Throwable> otherFailure = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    InstructionSnapshot snapshot = settlementService.transition(new TransitionCommand(
                            "INS-CONC-1", accepted.version(), SettlementStatus.PROCESSING, null));
                    success.incrementAndGet();
                    log.info("TRANSITION-WON instructionId=INS-CONC-1 version={} status={}",
                            snapshot.version(), snapshot.status());
                } catch (OptimisticLockConflictException e) {
                    conflicts.incrementAndGet();
                    log.info("TRANSITION-LOST instructionId=INS-CONC-1 expectedVersion={} actualVersion={} errorCode={}",
                            e.getExpectedVersion(), e.getActualVersion(), e.getErrorCode());
                } catch (Throwable t) {
                    log.error("TRANSITION-OTHER-FAILURE instructionId=INS-CONC-1", t);
                    otherFailure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdownNow();

        assertEquals(1, success.get(), "exactly one transition must succeed");
        assertEquals(threads - 1, conflicts.get(), "all losers must be distinguishable conflicts");
        assertEquals(null, otherFailure.get(),
                otherFailure.get() == null ? "" : otherFailure.get().toString());

        InstructionSnapshot finalSnapshot = settlementService.get("INS-CONC-1").orElseThrow();
        log.info("FINAL-SNAPSHOT instructionId=INS-CONC-1 status={} version={}",
                finalSnapshot.status(), finalSnapshot.version());
        assertEquals(SettlementStatus.PROCESSING, finalSnapshot.status());
        assertEquals(2L, finalSnapshot.version());
    }

    @Test
    void concurrent_submit_same_idempotency_key_effective_once() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger firstWins = new AtomicInteger();
        AtomicInteger duplicateHits = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SubmitResult result = settlementService.submit(
                            TestFixtures.submit("idem-conc-submit", "INS-CONC-2"));
                    if (result.duplicated()) {
                        duplicateHits.incrementAndGet();
                    } else {
                        firstWins.incrementAndGet();
                    }
                    log.info("SUBMIT-RESULT idempotencyKey=idem-conc-submit instructionId={} version={} duplicated={}",
                            result.instructionId(), result.version(), result.duplicated());
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdownNow();

        assertEquals(1, firstWins.get(), "exactly one first-time effect");
        assertEquals(threads - 1, duplicateHits.get(), "all others must be duplicate hits");
        assertEquals(null, failure.get(), failure.get() == null ? "" : failure.get().toString());

        InstructionSnapshot snapshot = settlementService.get("INS-CONC-2").orElseThrow();
        assertNotNull(snapshot);
        assertEquals(1L, snapshot.version());
        log.info("FINAL-SNAPSHOT idempotencyKey=idem-conc-submit instructionId=INS-CONC-2 status={} version={}",
                snapshot.status(), snapshot.version());
    }
}
