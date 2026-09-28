package com.github.lisama569.settlementledgerjava.api;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.ArchiveService;
import com.github.lisama569.settlementledgerjava.service.ReplayService;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotRebuildService;
import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.ArchiveResult;
import com.github.lisama569.settlementledgerjava.service.dto.AuditPage;
import com.github.lisama569.settlementledgerjava.service.dto.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportPage;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import com.github.lisama569.settlementledgerjava.service.exception.AuditDataException;
import com.github.lisama569.settlementledgerjava.service.exception.IllegalTransitionException;
import com.github.lisama569.settlementledgerjava.service.exception.OptimisticConflictException;
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
    private final SnapshotRebuildService snapshotRebuildService;
    private final ArchiveService archiveService;

    public SettlementController(SettlementService settlementService,
                                ReplayService replayService,
                                SnapshotRebuildService snapshotRebuildService,
                                ArchiveService archiveService) {
        this.settlementService = settlementService;
        this.replayService = replayService;
        this.snapshotRebuildService = snapshotRebuildService;
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

    /** 按指令重建完整业务快照（对账视图）。 */
    @GetMapping("/{id}/snapshot")
    public InstructionSnapshot snapshot(@PathVariable Long id) {
        return snapshotRebuildService.rebuild(id);
    }

    /**
     * 按范围批量导出对账快照。
     * cursor 为上一页最后一个指令 id；from/to 为受理时间窗口（[from, to)，ISO-8601）。
     * 历史链条问题以 kind=DATA_PROBLEM 的条目显式返回。
     */
    @GetMapping("/reconciliation/export")
    public SnapshotExportPage export(@RequestParam(required = false) Long cursor,
                                     @RequestParam(required = false) Instant from,
                                     @RequestParam(required = false) Instant to,
                                     @RequestParam(defaultValue = "100") int size) {
        return snapshotRebuildService.exportBatch(cursor, from, to, size);
    }

    /** 归档早于 cutoff 的审计记录（ISO-8601 时间）。 */
    @PostMapping("/audit/archive")
    public ArchiveResult archiveByTime(@RequestParam Instant before,
                                       @RequestParam(defaultValue = "500") int batchSize) {
        return archiveService.archiveBefore(before, batchSize);
    }

    /** 归档 id 不超过 maxIdInclusive 的审计记录（便于按明确边界操作）。 */
    @PostMapping("/audit/archive/up-to-id")
    public ArchiveResult archiveUpToId(@RequestParam long maxIdInclusive,
                                       @RequestParam(defaultValue = "500") int batchSize) {
        return archiveService.archiveUpToId(maxIdInclusive, batchSize);
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

    /** 历史链条/内容数据问题：422，带稳定错误码与指令 id，不与正常快照混淆。 */
    @ExceptionHandler(AuditDataException.class)
    public ResponseEntity<Map<String, Object>> auditDataProblem(AuditDataException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", e.getErrorCode(),
                "instructionId", e.getInstructionId() == null ? -1 : e.getInstructionId(),
                "message", e.getMessage() == null ? "" : e.getMessage()));
    }
}
