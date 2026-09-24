package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import com.github.lisama569.settlementledgerjava.service.exception.OptimisticConflictException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ConcurrencyConflictTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyConflictTest.class);

    @Autowired
    SettlementService settlementService;

    @Test
    void concurrentAdvance_sameVersion_exactlyOneWins() throws Exception {
        String key = "conc-" + UUID.randomUUID();
        SubmitResult submitted = settlementService.submit(
                new SubmitCommand(key, new BigDecimal("100.00"), "CNY", "alice", "bob"));
        log.info("snapshot after submit: idempotencyKey={} id={} status={} version={}",
                key, submitted.instructionId(), submitted.status(), submitted.version());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = IntStream.range(0, threads)
                .mapToObj(i -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        AdvanceResult r = settlementService.advance(
                                submitted.instructionId(), submitted.version(), SettlementStatus.VALIDATED);
                        log.info("advance SUCCESS: idempotencyKey={} status={} version={}",
                                key, r.status(), r.version());
                        return r;
                    } catch (OptimisticConflictException e) {
                        log.info("advance CONFLICT: idempotencyKey={} expectedVersion={} msg={}",
                                key, e.getExpectedVersion(), e.getMessage());
                        return e;
                    }
                }))
                .toList();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();

        List<Object> results = new java.util.ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long successes = results.stream().filter(r -> r instanceof AdvanceResult).count();
        long conflicts = results.stream().filter(r -> r instanceof OptimisticConflictException).count();
        log.info("outcome: idempotencyKey={} successes={} conflicts={}", key, successes, conflicts);
        assertEquals(1, successes, "exactly one advance must win");
        assertEquals(threads - 1, conflicts, "all others must see a distinguishable conflict");

        AdvanceResult winner = (AdvanceResult) results.stream()
                .filter(r -> r instanceof AdvanceResult).findFirst().orElseThrow();
        assertEquals(1L, winner.version());
        assertEquals(SettlementStatus.VALIDATED, winner.status());
    }

    @Test
    void advanceWithStaleVersion_throwsDistinguishableConflict() {
        String key = "stale-" + UUID.randomUUID();
        SubmitResult submitted = settlementService.submit(
                new SubmitCommand(key, new BigDecimal("50.00"), "CNY", "carol", "dave"));
        settlementService.advance(submitted.instructionId(), submitted.version(), SettlementStatus.VALIDATED);

        OptimisticConflictException e = assertThrows(OptimisticConflictException.class, () ->
                settlementService.advance(submitted.instructionId(), submitted.version(), SettlementStatus.SETTLED));
        log.info("stale version rejected: idempotencyKey={} expectedVersion={} msg={}",
                key, e.getExpectedVersion(), e.getMessage());
        assertEquals(submitted.instructionId(), e.getInstructionId());
    }
}
