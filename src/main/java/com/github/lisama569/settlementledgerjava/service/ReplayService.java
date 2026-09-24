package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.EventPayload;
import com.github.lisama569.settlementledgerjava.domain.EventType;
import com.github.lisama569.settlementledgerjava.domain.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.domain.SettlementEvent;
import com.github.lisama569.settlementledgerjava.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 基于审计事件重放还原指令状态。
 * 每个事件使 version 自增 1（SUBMITTED 后 version=1），
 * 与实时库 version 语义严格一致；历史 payload 经 {@link EventPayloads#decode} 归一化。
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final EventRepository eventRepository;

    public ReplayService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public Optional<InstructionSnapshot> replay(String instructionId) {
        List<SettlementEvent> events = eventRepository.findByInstruction(instructionId);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        InstructionSnapshot snapshot = fold(instructionId, events);
        log.info("replay instructionId={} status={} version={} amount={} {} channel={}",
                snapshot.instructionId(), snapshot.status(), snapshot.version(),
                snapshot.amount(), snapshot.currency(), snapshot.channel());
        return Optional.of(snapshot);
    }

    private InstructionSnapshot fold(String instructionId, List<SettlementEvent> events) {
        InstructionSnapshot current = null;
        long version = 0L;
        for (SettlementEvent event : events) {
            EventPayload payload = EventPayloads.decode(event.payloadVersion(), event.payload());
            if (event.eventType() == EventType.SUBMITTED) {
                version++;
                current = new InstructionSnapshot(
                        payload.instructionId(),
                        com.github.lisama569.settlementledgerjava.domain.SettlementStatus.ACCEPTED,
                        version,
                        payload.debtorAccount(),
                        payload.creditorAccount(),
                        payload.amount(),
                        payload.currency(),
                        payload.channel(),
                        null);
            } else if (event.eventType() == EventType.STATUS_CHANGED) {
                version++;
                if (current == null) {
                    throw new IllegalStateException(
                            "STATUS_CHANGED event before SUBMITTED for instruction " + instructionId);
                }
                String reason = payload.toStatus()
                        == com.github.lisama569.settlementledgerjava.domain.SettlementStatus.REJECTED
                        ? payload.reason() : null;
                current = new InstructionSnapshot(
                        current.instructionId(), payload.toStatus(), version,
                        current.debtorAccount(), current.creditorAccount(),
                        current.amount(), current.currency(), current.channel(), reason);
            } else {
                throw new IllegalStateException("unknown event type: " + event.eventType());
            }
        }
        return current;
    }
}
