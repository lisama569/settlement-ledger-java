package com.github.lisama569.settlementledgerjava.api;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.ArchiveService;
import com.github.lisama569.settlementledgerjava.service.ReplayService;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotService;
import com.github.lisama569.settlementledgerjava.service.dto.*;
import com.github.lisama569.settlementledgerjava.service.exception.IllegalTransitionException;
import com.github.lisama569.settlementledgerjava.service.exception.OptimisticConflictException;
import com.github.lisama569.settlementledgerjava.service.exception.SnapshotDataIntegrityException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;
    private final ReplayService replayService;
    private final SnapshotService snapshotService;
    private final ArchiveService archiveService;

    public SettlementController(SettlementService settlementService,
                                ReplayService replayService,
                                SnapshotService snapshotService,
                                ArchiveService archiveService) {
        this.settlementService = settlementService;
        this.replayService = replayService;
        this.snapshotService = snapshotService;
        this.archiveService = archiveService;
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

    /** 按指令重建完整业务快照。 */
    @GetMapping("/{id}/snapshot")
    public SettlementSnapshot snapshot(@PathVariable Long id) {
        return snapshotService.rebuild(id);
    }

    /** 按游标/时间窗口批量导出对账快照。 */
    @GetMapping("/snapshots")
    public SnapshotExportPage exportSnapshots(@RequestParam(required = false) Instant from,
                                              @RequestParam(required = false) Instant to,
                                              @RequestParam(required = false) Long afterId,
                                              @RequestParam(defaultValue = "50") int size) {
        return snapshotService.exportRange(new SnapshotExportRequest(from, to, afterId, size));
    }

    /** 归档较早的审计历史（runId 幂等，重试只生效一次）。 */
    @PostMapping("/archives")
    public ArchiveResult archive(@RequestParam String runId, @RequestParam Instant cutoff) {
        return archiveService.archive(runId, cutoff);
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

    /** 快照数据质量问题：422，错误码标明是哪类历史数据问题。 */
    @ExceptionHandler(SnapshotDataIntegrityException.class)
    public ResponseEntity<Map<String, Object>> dataIntegrity(SnapshotDataIntegrityException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", e.getCode().name(),
                "instructionId", String.valueOf(e.getInstructionId()),
                "message", e.getMessage()));
    }
}
