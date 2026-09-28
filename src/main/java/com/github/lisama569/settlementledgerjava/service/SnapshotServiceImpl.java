package com.github.lisama569.settlementledgerjava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lisama569.settlementledgerjava.audit.AuditHistoryEntry;
import com.github.lisama569.settlementledgerjava.audit.AuditHistoryReader;
import com.github.lisama569.settlementledgerjava.audit.AuditPayloadUpcaster;
import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.repository.SettlementInstructionRepository;
import com.github.lisama569.settlementledgerjava.service.dto.SettlementSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportItem;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportPage;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportRequest;
import com.github.lisama569.settlementledgerjava.service.exception.SnapshotDataIntegrityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

@Service
public class SnapshotServiceImpl implements SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotServiceImpl.class);

    private final SettlementInstructionRepository instructionRepository;
    private final AuditHistoryReader historyReader;
    private final AuditPayloadUpcaster upcaster;

    public SnapshotServiceImpl(SettlementInstructionRepository instructionRepository,
                               AuditHistoryReader historyReader,
                               AuditPayloadUpcaster upcaster) {
        this.instructionRepository = instructionRepository;
        this.historyReader = historyReader;
        this.upcaster = upcaster;
    }

    /**
     * 按指令重建完整业务快照：受理事件 payload（确定性补齐后）提供业务属性，
     * 状态/版本/最后变更时间取自实时行，保证与实时读一致。
     */
    @Override
    @Transactional(readOnly = true)
    public SettlementSnapshot rebuild(Long instructionId) {
        SettlementInstruction instruction = instructionRepository.findById(instructionId)
                .orElseThrow(() -> new SnapshotDataIntegrityException(
                        SnapshotDataIntegrityException.Code.INSTRUCTION_NOT_FOUND,
                        instructionId, "Instruction not found: " + instructionId));

        List<AuditHistoryEntry> history = historyReader.historyOf(instructionId);
        SettlementStatus replayed = validateChain(instructionId, history);
        if (replayed != instruction.getStatus()) {
            throw new SnapshotDataIntegrityException(
                    SnapshotDataIntegrityException.Code.CHAIN_BROKEN, instructionId,
                    "Audit chain does not reach live status for instruction " + instructionId
                            + ": replayed=" + replayed + " live=" + instruction.getStatus());
        }

        AuditHistoryEntry submit = history.stream()
                .filter(e -> "SUBMITTED".equals(e.eventType()))
                .findFirst()
                .orElseThrow(() -> new SnapshotDataIntegrityException(
                        SnapshotDataIntegrityException.Code.NO_SUBMIT_EVENT,
                        instructionId, "No SUBMITTED event for instruction " + instructionId));

        JsonNode payload = upcastSafely(instructionId, submit);

        TreeMap<String, String> attributes = new TreeMap<>();
        payload.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            attributes.put(field.getKey(), value.isTextual() ? value.asText() : value.toString());
        });

        SettlementSnapshot snapshot = new SettlementSnapshot(
                instruction.getId(),
                instruction.getIdempotencyKey(),
                instruction.getAmount(),
                instruction.getCurrency(),
                instruction.getPayer(),
                instruction.getPayee(),
                instruction.getStatus(),
                instruction.getVersion(),
                instruction.getUpdatedAt(),
                instruction.getCreatedAt(),
                attributes);

        log.info("snapshot rebuilt: idempotencyKey={} instructionId={} status={} version={} events={}",
                instruction.getIdempotencyKey(), instructionId,
                snapshot.status(), snapshot.version(), history.size());
        return snapshot;
    }

    private SettlementStatus validateChain(Long instructionId, List<AuditHistoryEntry> history) {
        if (history.isEmpty()) {
            throw new SnapshotDataIntegrityException(
                    SnapshotDataIntegrityException.Code.NO_SUBMIT_EVENT,
                    instructionId, "No audit history for instruction " + instructionId);
        }
        SettlementStatus state = null;
        long expectedSeq = 1L;
        for (AuditHistoryEntry entry : history) {
            if (entry.seq() != expectedSeq) {
                throw new SnapshotDataIntegrityException(
                        SnapshotDataIntegrityException.Code.CHAIN_BROKEN, instructionId,
                        "Audit chain broken for instruction " + instructionId
                                + ": expected seq=" + expectedSeq + " actual seq=" + entry.seq());
            }
            if (entry.fromStatus() != state) {
                throw new SnapshotDataIntegrityException(
                        SnapshotDataIntegrityException.Code.CHAIN_BROKEN, instructionId,
                        "Audit chain broken for instruction " + instructionId
                                + ": expected from=" + state + " actual from=" + entry.fromStatus());
            }
            state = entry.toStatus();
            expectedSeq++;
        }
        return state;
    }

    private JsonNode upcastSafely(Long instructionId, AuditHistoryEntry submit) {
        try {
            return upcaster.upcast(submit.schemaVersion(), submit.payload());
        } catch (IllegalStateException e) {
            throw new SnapshotDataIntegrityException(
                    SnapshotDataIntegrityException.Code.PAYLOAD_UNPARSEABLE, instructionId,
                    "Unparseable submit payload for instruction " + instructionId
                            + " (schemaVersion=" + submit.schemaVersion() + ")", e);
        }
    }

    /**
     * 按范围批量重建：游标/时间窗口分页，单条数据问题以 error 项返回，不静默跳过。
     */
    @Override
    @Transactional(readOnly = true)
    public SnapshotExportPage exportRange(SnapshotExportRequest request) {
        if (request.size() <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        List<SettlementInstruction> page = instructionRepository.findExportPage(
                request.afterInstructionId(), request.from(), request.to(),
                PageRequest.of(0, request.size()));

        List<SnapshotExportItem> items = new ArrayList<>(page.size());
        for (SettlementInstruction instruction : page) {
            try {
                items.add(SnapshotExportItem.of(rebuild(instruction.getId())));
            } catch (SnapshotDataIntegrityException e) {
                log.warn("export item skipped as data issue: instructionId={} code={} msg={}",
                        instruction.getId(), e.getCode(), e.getMessage());
                items.add(SnapshotExportItem.error(instruction.getId(),
                        e.getCode().name(), e.getMessage()));
            }
        }
        Long nextCursor = page.size() < request.size() ? null : page.get(page.size() - 1).getId();
        log.info("snapshot export: from={} to={} afterId={} size={} items={} nextCursor={}",
                request.from(), request.to(), request.afterInstructionId(),
                request.size(), items.size(), nextCursor);
        return new SnapshotExportPage(items, nextCursor);
    }
}
