package com.github.lisama569.settlementledgerjava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lisama569.settlementledgerjava.audit.AuditEventView;
import com.github.lisama569.settlementledgerjava.audit.AuditHistoryReader;
import com.github.lisama569.settlementledgerjava.audit.AuditPayloadUpcaster;
import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.repository.SettlementInstructionRepository;
import com.github.lisama569.settlementledgerjava.service.dto.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportEntry;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportPage;
import com.github.lisama569.settlementledgerjava.service.exception.AuditChainBrokenException;
import com.github.lisama569.settlementledgerjava.service.exception.AuditDataException;
import com.github.lisama569.settlementledgerjava.service.exception.AuditPayloadUnparseableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class SnapshotRebuildServiceImpl implements SnapshotRebuildService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRebuildServiceImpl.class);

    /** 已提升为快照一等字段的 payload 键，不再重复放进 attributes。 */
    private static final List<String> PROMOTED_FIELDS =
            List.of("idempotencyKey", "amount", "currency", "payer", "payee", "channel");

    private final SettlementInstructionRepository instructionRepository;
    private final AuditHistoryReader auditHistoryReader;
    private final AuditPayloadUpcaster upcaster;

    public SnapshotRebuildServiceImpl(SettlementInstructionRepository instructionRepository,
                                      AuditHistoryReader auditHistoryReader,
                                      AuditPayloadUpcaster upcaster) {
        this.instructionRepository = instructionRepository;
        this.auditHistoryReader = auditHistoryReader;
        this.upcaster = upcaster;
    }

    /**
     * 基于完整审计链条重建业务快照：
     * SUBMITTED 事件（经确定性 upcast）提供受理业务属性；沿链条推进状态、
     * 版本（受理为 0，每次状态变更 +1）与最后变更时间。结果与实时读取一致。
     */
    @Override
    @Transactional(readOnly = true)
    public InstructionSnapshot rebuild(Long instructionId) {
        List<AuditEventView> chain = auditHistoryReader.loadChain(instructionId);
        InstructionSnapshot snapshot = rebuildFromChain(instructionId, chain);

        SettlementInstruction live = instructionRepository.findById(instructionId).orElse(null);
        if (live != null) {
            // 与实时读到的当前状态、版本、最后变更时间严格一致。
            if (snapshot.status() != live.getStatus()
                    || snapshot.version() != live.getVersion()
                    || !snapshot.lastChangedAt().equals(live.getUpdatedAt())) {
                throw new AuditChainBrokenException(instructionId,
                        "Rebuilt snapshot diverges from live state: rebuilt=(status=" + snapshot.status()
                                + ",version=" + snapshot.version() + ",lastChangedAt=" + snapshot.lastChangedAt()
                                + ") live=(status=" + live.getStatus() + ",version=" + live.getVersion()
                                + ",updatedAt=" + live.getUpdatedAt() + ")");
            }
        }
        log.info("snapshot rebuilt: idempotencyKey={} instructionId={} status={} version={} lastChangedAt={}",
                snapshot.idempotencyKey(), instructionId, snapshot.status(), snapshot.version(),
                snapshot.lastChangedAt());
        return snapshot;
    }

    @Override
    @Transactional(readOnly = true)
    public SnapshotExportPage exportBatch(Long cursor, Instant fromTime, Instant toTime, int size) {
        int pageSize = size <= 0 ? 50 : size;
        List<Long> ids = instructionRepository.findIdsForExport(
                cursor, fromTime, toTime, PageRequest.of(0, pageSize));

        List<SnapshotExportEntry> entries = new ArrayList<>(ids.size());
        for (Long id : ids) {
            try {
                entries.add(SnapshotExportEntry.ok(rebuild(id)));
            } catch (AuditDataException e) {
                // 数据问题显式暴露给调用方：不静默跳过，也不伪装成正常快照。
                log.warn("export data problem: instructionId={} errorCode={} message={}",
                        id, e.getErrorCode(), e.getMessage());
                entries.add(SnapshotExportEntry.problem(id, e.getErrorCode(), e.getMessage()));
            }
        }
        Long nextCursor = ids.size() < pageSize ? null : ids.get(ids.size() - 1);
        log.info("export batch: cursor={} from={} to={} size={} returned={} problems={} nextCursor={}",
                cursor, fromTime, toTime, pageSize, entries.size(),
                entries.stream().filter(SnapshotExportEntry::isProblem).count(), nextCursor);
        return new SnapshotExportPage(entries, nextCursor);
    }

    private InstructionSnapshot rebuildFromChain(Long instructionId, List<AuditEventView> chain) {
        if (chain.isEmpty()) {
            throw new AuditChainBrokenException(instructionId, "Audit chain is empty");
        }
        AuditEventView first = chain.get(0);
        if (!"SUBMITTED".equals(first.getEventType()) || first.getSeq() != 1L
                || first.getFromStatus() != null || first.getToStatus() != SettlementStatus.RECEIVED) {
            throw new AuditChainBrokenException(instructionId,
                    "First audit event is not a valid SUBMITTED event: seq=" + first.getSeq()
                            + " eventType=" + first.getEventType() + " from=" + first.getFromStatus()
                            + " to=" + first.getToStatus());
        }

        JsonNode submitted = parsePayload(first);
        String idempotencyKey = textField(instructionId, submitted, "idempotencyKey", first);
        BigDecimal amount = amountField(instructionId, submitted, first);
        String currency = textField(instructionId, submitted, "currency", first);
        String payer = textField(instructionId, submitted, "payer", first);
        String payee = textField(instructionId, submitted, "payee", first);
        String channel = textField(instructionId, submitted, "channel", first);
        Map<String, String> attributes = collectAttributes(submitted);

        SettlementStatus state = SettlementStatus.RECEIVED;
        long version = 0L;
        Instant lastChangedAt = first.getCreatedAt();

        for (int i = 1; i < chain.size(); i++) {
            AuditEventView event = chain.get(i);
            if (!"STATUS_CHANGED".equals(event.getEventType())) {
                throw new AuditChainBrokenException(instructionId,
                        "Unexpected eventType at seq=" + event.getSeq() + ": " + event.getEventType());
            }
            if (event.getFromStatus() != state) {
                throw new AuditChainBrokenException(instructionId,
                        "Status mismatch at seq=" + event.getSeq() + ": expected from=" + state
                                + " actual from=" + event.getFromStatus());
            }
            if (event.getToStatus() == null) {
                throw new AuditChainBrokenException(instructionId,
                        "Missing toStatus at seq=" + event.getSeq());
            }
            state = event.getToStatus();
            version++;
            lastChangedAt = event.getCreatedAt();
            // 解析每个事件 payload 以及时暴露不可解析记录；内容不参与业务字段重建。
            parsePayload(event);
        }

        return new InstructionSnapshot(instructionId, idempotencyKey, amount, currency, payer, payee,
                channel, state, version, lastChangedAt, attributes);
    }

    private JsonNode parsePayload(AuditEventView event) {
        try {
            return upcaster.upcast(event.getSchemaVersion(), event.getPayload());
        } catch (RuntimeException e) {
            throw new AuditPayloadUnparseableException(event.getInstructionId(),
                    "Cannot parse payload at seq=" + event.getSeq()
                            + " schemaVersion=" + event.getSchemaVersion(), e);
        }
    }

    private String textField(Long instructionId, JsonNode node, String field, AuditEventView event) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            // upcaster 已覆盖已知版本的缺字段；走到这里说明结构异常，明确报出而不是丢字段。
            throw new AuditPayloadUnparseableException(instructionId,
                    "Missing field '" + field + "' after upcast at seq=" + event.getSeq()
                            + " schemaVersion=" + event.getSchemaVersion(), null);
        }
        if (!value.isTextual()) {
            throw new AuditPayloadUnparseableException(instructionId,
                    "Field '" + field + "' is not a string at seq=" + event.getSeq(), null);
        }
        return value.asText();
    }

    private BigDecimal amountField(Long instructionId, JsonNode node, AuditEventView event) {
        JsonNode value = node.get("amount");
        if (value == null || value.isNull()) {
            throw new AuditPayloadUnparseableException(instructionId,
                    "Missing 'amount' after upcast at seq=" + event.getSeq(), null);
        }
        try {
            if (value.isTextual()) {
                return new BigDecimal(value.asText()).stripTrailingZeros();
            }
            if (value.isNumber()) {
            return value.decimalValue().stripTrailingZeros();
            }
            throw new IllegalArgumentException("amount is not a number");
        } catch (RuntimeException e) {
            throw new AuditPayloadUnparseableException(instructionId,
                    "Unparseable 'amount' at seq=" + event.getSeq() + ": " + value, e);
        }
    }

    /** 收集未被提升为一等字段的标量业务属性，TreeMap 保证 key 顺序确定。 */
    private Map<String, String> collectAttributes(JsonNode submitted) {
        Map<String, String> attributes = new TreeMap<>();
        submitted.fieldNames().forEachRemaining(name -> {
            if (PROMOTED_FIELDS.contains(name)) {
                return;
            }
            JsonNode value = submitted.get(name);
            if (value != null && value.isValueNode()) {
                attributes.put(name, value.asText());
            }
        });
        return Map.copyOf(attributes);
    }
}
