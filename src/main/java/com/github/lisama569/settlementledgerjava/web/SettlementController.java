package com.github.lisama569.settlementledgerjava.web;

import com.github.lisama569.settlementledgerjava.domain.SubmitCommand;
import com.github.lisama569.settlementledgerjava.domain.TransitionCommand;
import com.github.lisama569.settlementledgerjava.service.ReplayService;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settlement")
public class SettlementController {

    private final SettlementService settlementService;
    private final ReplayService replayService;

    public SettlementController(SettlementService settlementService, ReplayService replayService) {
        this.settlementService = settlementService;
        this.replayService = replayService;
    }

    @PostMapping("/instructions")
    public ResponseEntity<SubmitHttpResponse> submit(@RequestBody SubmitHttpRequest request) {
        var result = settlementService.submit(new SubmitCommand(
                request.idempotencyKey(), request.instructionId(), request.debtorAccount(),
                request.creditorAccount(), request.amount(), request.currency(),
                request.channel(), request.submittedBy()));
        HttpStatus status = result.duplicated() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new SubmitHttpResponse(
                result.instructionId(), result.status(), result.version(), result.duplicated()));
    }

    @PostMapping("/instructions/{id}/transitions")
    public SnapshotHttpResponse transition(@PathVariable String id,
                                           @RequestBody TransitionHttpRequest request) {
        return SnapshotHttpResponse.from(settlementService.transition(new TransitionCommand(
                id, request.expectedVersion() == null ? -1L : request.expectedVersion(),
                request.targetStatus(), request.reason())));
    }

    @GetMapping("/instructions/{id}")
    public ResponseEntity<SnapshotHttpResponse> get(@PathVariable String id) {
        return settlementService.get(id)
                .map(s -> ResponseEntity.ok(SnapshotHttpResponse.from(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/instructions/{id}/replay")
    public ResponseEntity<SnapshotHttpResponse> replay(@PathVariable String id) {
        return replayService.replay(id)
                .map(s -> ResponseEntity.ok(SnapshotHttpResponse.from(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/events")
    public EventPageHttpResponse events(@RequestParam(required = false) Long cursor,
                                        @RequestParam(defaultValue = "50") int limit) {
        return EventPageHttpResponse.from(settlementService.queryEvents(cursor, limit));
    }
}
