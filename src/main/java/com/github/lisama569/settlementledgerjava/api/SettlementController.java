package com.github.lisama569.settlementledgerjava.api;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.ReplayService;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.AuditPage;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import com.github.lisama569.settlementledgerjava.service.exception.IllegalTransitionException;
import com.github.lisama569.settlementledgerjava.service.exception.OptimisticConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;
    private final ReplayService replayService;

    public SettlementController(SettlementService settlementService, ReplayService replayService) {
        this.settlementService = settlementService;
        this.replayService = replayService;
    }

    @PostMapping
    public SubmitResult submit(@RequestBody SubmitCommand command) {
        return settlementService.submit(command);
    }

    @PostMapping("/{id}/advance")
    public AdvanceResult advance(@PathVariable Long id,
                                 @RequestParam long expectedVersion,
                                 @RequestParam SettlementStatus target) {
        return settlementService.advance(id, expectedVersion, target);
    }

    @GetMapping("/{id}/replay")
    public Map<String, Object> replay(@PathVariable Long id) {
        return Map.of("instructionId", id, "status", replayService.replayStatus(id));
    }

    @GetMapping("/audit")
    public AuditPage audit(@RequestParam(required = false) Long cursor,
                           @RequestParam(defaultValue = "50") int size) {
        return settlementService.auditPage(cursor, size);
    }

    /** 并发冲突：409，调用方可与其他失败区分。 */
    @ExceptionHandler(OptimisticConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(OptimisticConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "OPTIMISTIC_CONFLICT",
                "instructionId", e.getInstructionId(),
                "expectedVersion", e.getExpectedVersion(),
                "message", e.getMessage()));
    }

    /** 非法状态迁移：422。 */
    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<Map<String, Object>> illegalTransition(IllegalTransitionException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "ILLEGAL_TRANSITION",
                "message", e.getMessage()));
    }
}
