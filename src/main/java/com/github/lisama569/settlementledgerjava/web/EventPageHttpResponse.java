package com.github.lisama569.settlementledgerjava.web;

import com.github.lisama569.settlementledgerjava.domain.SettlementEvent;

import java.time.Instant;
import java.util.List;

public record EventPageHttpResponse(List<EventView> events, Long nextCursor) {

    public record EventView(long seqNo, String instructionId, String eventType,
                            int payloadVersion, String payload, Instant occurredAt) {
        static EventView from(SettlementEvent e) {
            return new EventView(e.seqNo(), e.instructionId(), e.eventType().name(),
                    e.payloadVersion(), e.payload(), e.occurredAt());
        }
    }

    public static EventPageHttpResponse from(com.github.lisama569.settlementledgerjava.domain.EventPage page) {
        return new EventPageHttpResponse(page.events().stream().map(EventView::from).toList(),
                page.nextCursor());
    }
}
