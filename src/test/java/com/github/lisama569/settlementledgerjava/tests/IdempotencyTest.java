package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
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
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    @Autowired
    SettlementService settlementService;

    @Autowired
    AuditRecordRepository auditRecordRepository;

    @Test
    void duplicateSubmit_returnsFirstResult_markedDuplicate() {
        String key = "idem-" + UUID.randomUUID();
        SubmitCommand cmd = new SubmitCommand(key, new BigDecimal("88.00"), "CNY", "erin", "frank");

        SubmitResult first = settlementService.submit(cmd);
        SubmitResult second = settlementService.submit(cmd);

        log.info("first:  idempotencyKey={} id={} status={} version={} duplicate={}",
                key, first.instructionId(), first.status(), first.version(), first.duplicate());
        log.info("second: idempotencyKey={} id={} status={} version={} duplicate={}",
                key, second.instructionId(), second.status(), second.version(), second.duplicate());

        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertEquals(first.instructionId(), second.instructionId());
        assertEquals(first.status(), second.status());
        assertEquals(first.version(), second.version());
        assertEquals(1, auditRecordRepository.findByInstructionIdOrderBySeqAsc(first.instructionId()).size(),
                "duplicate submit must not append extra audit records");
    }

    @Test
    void concurrentSubmit_sameKey_exactlyOneTakesEffect() throws Exception {
        String key = "idem-conc-" + UUID.randomUUID();
        SubmitCommand cmd = new SubmitCommand(key, new BigDecimal("66.00"), "USD", "gina", "hank");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SubmitResult>> futures = IntStream.range(0, threads)
                .mapToObj(i -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return settlementService.submit(cmd);
                }))
                .toList();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();

        List<SubmitResult> results = new java.util.ArrayList<>();
        for (Future<SubmitResult> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long effective = results.stream().filter(r -> !r.duplicate()).count();
        long duplicates = results.stream().filter(SubmitResult::duplicate).count();
        long distinctIds = results.stream().map(SubmitResult::instructionId).distinct().count();
        log.info("concurrent submit outcome: idempotencyKey={} effective={} duplicates={} distinctIds={}",
                key, effective, duplicates, distinctIds);
        results.forEach(r -> log.info("  result: idempotencyKey={} id={} status={} version={} duplicate={}",
                key, r.instructionId(), r.status(), r.version(), r.duplicate()));

        assertEquals(1, effective, "exactly one submit may take effect");
        assertEquals(threads - 1, duplicates);
        assertEquals(1, distinctIds, "all callers must observe the same instruction");
    }
}
