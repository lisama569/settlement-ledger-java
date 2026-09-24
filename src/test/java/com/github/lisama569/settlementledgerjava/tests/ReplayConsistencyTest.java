package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.repository.SettlementInstructionRepository;
import com.github.lisama569.settlementledgerjava.service.ReplayService;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReplayConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayConsistencyTest.class);

    @Autowired
    SettlementService settlementService;

    @Autowired
    ReplayService replayService;

    @Autowired
    SettlementInstructionRepository instructionRepository;

    @Test
    void replayedStatus_matchesLiveStatus_afterEachStep() {
        String key = "replay-" + UUID.randomUUID();
        SubmitResult submitted = settlementService.submit(
                new SubmitCommand(key, new BigDecimal("120.00"), "CNY", "ivy", "jack"));

        assertConsistent(key, submitted.instructionId());

        AdvanceResult validated = settlementService.advance(
                submitted.instructionId(), submitted.version(), SettlementStatus.VALIDATED);
        assertConsistent(key, submitted.instructionId());

        AdvanceResult settled = settlementService.advance(
                submitted.instructionId(), validated.version(), SettlementStatus.SETTLED);
        assertConsistent(key, submitted.instructionId());

        SettlementInstruction live = instructionRepository.findById(submitted.instructionId()).orElseThrow();
        log.info("final snapshot: idempotencyKey={} status={} version={}",
                key, live.getStatus(), live.getVersion());
        assertEquals(SettlementStatus.SETTLED, live.getStatus());
        assertEquals(2L, settled.version());
    }

    @Test
    void replayedStatus_matchesLiveStatus_onFailurePath() {
        String key = "replay-fail-" + UUID.randomUUID();
        SubmitResult submitted = settlementService.submit(
                new SubmitCommand(key, new BigDecimal("5.00"), "CNY", "kate", "leo"));
        settlementService.advance(submitted.instructionId(), submitted.version(), SettlementStatus.FAILED);

        SettlementStatus replayed = replayService.replayStatus(submitted.instructionId());
        SettlementInstruction live = instructionRepository.findById(submitted.instructionId()).orElseThrow();
        log.info("failure path: idempotencyKey={} replayed={} live={} version={}",
                key, replayed, live.getStatus(), live.getVersion());
        assertEquals(live.getStatus(), replayed);
        assertEquals(SettlementStatus.FAILED, replayed);
    }

    private void assertConsistent(String key, Long instructionId) {
        SettlementStatus replayed = replayService.replayStatus(instructionId);
        SettlementStatus live = instructionRepository.findById(instructionId).orElseThrow().getStatus();
        log.info("consistency check: idempotencyKey={} replayed={} live={}", key, replayed, live);
        assertEquals(live, replayed, "replayed status must equal live status");
    }
}
