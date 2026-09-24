package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.EventPage;
import com.github.lisama569.settlementledgerjava.domain.EventPayload;
import com.github.lisama569.settlementledgerjava.domain.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.domain.SettlementEvent;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.domain.SubmitResult;
import com.github.lisama569.settlementledgerjava.domain.TransitionCommand;
import com.github.lisama569.settlementledgerjava.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ReplayAndPaginationTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayAndPaginationTest.class);

    @Autowired
    private SettlementService settlementService;
    @Autowired
    private ReplayService replayService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EventRepository eventRepository;

    @Test
    void replay_matches_live_state_through_full_lifecycle() {
        SubmitResult accepted = settlementService.submit(TestFixtures.submit("idem-replay-1", "INS-REPLAY-1"));
        settlementService.transition(new TransitionCommand(
                "INS-REPLAY-1", accepted.version(), SettlementStatus.PROCESSING, null));
        settlementService.transition(new TransitionCommand(
                "INS-REPLAY-1", accepted.version() + 1, SettlementStatus.SETTLED, null));

        InstructionSnapshot live = settlementService.get("INS-REPLAY-1").orElseThrow();
        InstructionSnapshot replayed = replayService.replay("INS-REPLAY-1").orElseThrow();

        log.info("replay-compare idempotencyKey=idem-replay-1 liveVersion={} replayVersion={} liveStatus={} replayStatus={}",
                live.version(), replayed.version(), live.status(), replayed.status());
        assertEquals(live, replayed);
    }

    @Test
    void cursor_pagination_no_gap_no_duplicate_under_continuous_writes() {
        int pageSize = 5;
        long baseline = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq_no),0) FROM settlement_events", Long.class);

        // 先写入 7 条事件（提交 4 条指令 + 推进 3 次）
        for (int i = 1; i <= 4; i++) {
            settlementService.submit(TestFixtures.submit("idem-page-" + i, "INS-PAGE-" + i));
        }
        settlementService.transition(new TransitionCommand("INS-PAGE-1", 1, SettlementStatus.PROCESSING, null));
        settlementService.transition(new TransitionCommand("INS-PAGE-2", 1, SettlementStatus.PROCESSING, null));
        settlementService.transition(new TransitionCommand("INS-PAGE-3", 1, SettlementStatus.PROCESSING, null));

        List<Long> seen = new ArrayList<>();
        Long cursor = baseline; // 首页边界：只看本测试产生的事件
        int iterations = 0;
        int injectedExtra = 0;
        while (true) {
            EventPage current = settlementService.queryEvents(cursor, pageSize);
            for (SettlementEvent event : current.events()) {
                seen.add(event.seqNo());
            }
            if (current.nextCursor() == null) {
                // 到达当前尾端后再追加写入，验证持续写入下新事件能用游标继续取到
                if (injectedExtra >= 2) {
                    break;
                }
                int extra = injectedExtra + 100;
                settlementService.submit(TestFixtures.submit("idem-page-extra-" + extra, "INS-PAGE-EX-" + extra));
                long injectedSeq = jdbcTemplate.queryForObject(
                        "SELECT MAX(seq_no) FROM settlement_events", Long.class);
                cursor = injectedSeq; // 注入的事件自身算作已消费，下一页只看更新的写入
                injectedExtra++;
            } else {
                cursor = current.nextCursor();
            }
            iterations++;
            assertTrue(iterations < 100, "pagination must terminate");
        }

        long distinct = seen.stream().distinct().count();
        log.info("pagination totalFetched={} distinct={} pages={} range=[{},{}]",
                seen.size(), distinct, iterations,
                seen.isEmpty() ? 0 : seen.get(0), seen.isEmpty() ? 0 : seen.get(seen.size() - 1));
        assertEquals(distinct, seen.size(), "must not contain duplicates");
        // 游标键集分页严格按 seqNo 升序，天然有序
        List<Long> sorted = seen.stream().sorted().toList();
        assertEquals(sorted, seen, "pages must be returned in ascending cursor order");
        assertTrue(seen.stream().allMatch(seq -> seq > baseline), "must not include pre-test rows");
    }

    @Test
    void legacy_v1_payload_is_deterministically_backfilled_on_replay() {
        // 模拟结构演进前落库的历史数据：payload_version=1 的 SUBMITTED 事件缺少 channel
        jdbcTemplate.update("""
                INSERT INTO settlement_events(instruction_id, event_type, payload_version, payload, occurred_at)
                VALUES ('INS-LEGACY-1', 'SUBMITTED', 1,
                        '{"instructionId":"INS-LEGACY-1","debtorAccount":"D1","creditorAccount":"C1",'
                        || '"amount":12.34,"currency":"USD","submittedBy":"legacy-user"}',
                        CURRENT_TIMESTAMP)
                """);

        InstructionSnapshot replayed = replayService.replay("INS-LEGACY-1").orElseThrow();
        log.info("legacy-replay instructionId=INS-LEGACY-1 status={} version={} currency={} channel(backfilled)={}",
                replayed.status(), replayed.version(), replayed.currency(), replayed.channel());

        assertEquals(SettlementStatus.ACCEPTED, replayed.status());
        assertEquals(1L, replayed.version());
        assertEquals(EventPayloads.DEFAULT_CHANNEL, replayed.channel(),
                "missing field in v1 payload must be backfilled with deterministic default");
        SettlementEvent rawEvent = eventRepository.findByInstruction("INS-LEGACY-1").get(0);
        assertEquals(1, rawEvent.payloadVersion(), "history row must retain its original payload version");
        EventPayload decoded = EventPayloads.decode(rawEvent.payloadVersion(), rawEvent.payload());
        assertEquals("legacy-user", decoded.submittedBy());
        assertEquals("D1", decoded.debtorAccount());
        assertTrue(rawEvent.payload().contains("legacy-user"));
    }
}
