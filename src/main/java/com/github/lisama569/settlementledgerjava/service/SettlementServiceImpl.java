package com.github.lisama569.settlementledgerjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.lisama569.settlementledgerjava.audit.AuditHistoryReader;
import com.github.lisama569.settlementledgerjava.audit.AuditPayloadUpcaster;
import com.github.lisama569.settlementledgerjava.domain.AuditRecord;
import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.repository.AuditRecordRepository;
import com.github.lisama569.settlementledgerjava.repository.SettlementInstructionRepository;
import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.AuditPage;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import com.github.lisama569.settlementledgerjava.service.exception.IllegalTransitionException;
import com.github.lisama569.settlementledgerjava.service.exception.OptimisticConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SettlementServiceImpl implements SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementServiceImpl.class);

    /** 状态机：允许的迁移。 */
    private static final Map<SettlementStatus, Set<SettlementStatus>> ALLOWED = Map.of(
            SettlementStatus.RECEIVED, EnumSet.of(SettlementStatus.VALIDATED, SettlementStatus.FAILED),
            SettlementStatus.VALIDATED, EnumSet.of(SettlementStatus.SETTLED, SettlementStatus.FAILED),
            SettlementStatus.SETTLED, EnumSet.noneOf(SettlementStatus.class),
            SettlementStatus.FAILED, EnumSet.noneOf(SettlementStatus.class));

    private final SettlementInstructionRepository instructionRepository;
    private final AuditRecordRepository auditRecordRepository;
    private final AuditHistoryReader auditHistoryReader;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SettlementServiceImpl(SettlementInstructionRepository instructionRepository,
                                 AuditRecordRepository auditRecordRepository,
                                 AuditHistoryReader auditHistoryReader,
                                 TransactionTemplate transactionTemplate) {
        this.instructionRepository = instructionRepository;
        this.auditRecordRepository = auditRecordRepository;
        this.auditHistoryReader = auditHistoryReader;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 幂等受理：指令与首条审计记录在同一事务内落库。
     * 命中唯一约束（含并发竞争）时回读首次提交的结果，duplicate=true。
     */
    @Override
    public SubmitResult submit(SubmitCommand command) {
        try {
            return transactionTemplate.execute(tx -> doSubmit(command));
        } catch (DataIntegrityViolationException e) {
            SettlementInstruction existing = instructionRepository
                    .findByIdempotencyKey(command.idempotencyKey())
                    .orElseThrow(() -> e);
            log.info("idempotent replay: idempotencyKey={} status={} version={}",
                    existing.getIdempotencyKey(), existing.getStatus(), existing.getVersion());
            return new SubmitResult(existing.getId(), existing.getIdempotencyKey(),
                    existing.getStatus(), existing.getVersion(), true);
        }
    }

    private SubmitResult doSubmit(SubmitCommand command) {
        Instant now = Instant.now();
        SettlementInstruction instruction = new SettlementInstruction(
                command.idempotencyKey(), command.amount(), command.currency(),
                command.payer(), command.payee(), now);
        instruction = instructionRepository.saveAndFlush(instruction);

        ObjectNode payload = objectMapper.createObjectNode()
                .put("idempotencyKey", command.idempotencyKey())
                .put("amount", command.amount().toPlainString())
                .put("currency", command.currency())
                .put("payer", command.payer())
                .put("payee", command.payee())
                .put("channel", "API")
                .put("businessType", "PAYMENT");
        AuditRecord audit = new AuditRecord(instruction.getId(), 1L, null, SettlementStatus.RECEIVED,
                "SUBMITTED", AuditPayloadUpcaster.CURRENT_SCHEMA_VERSION, payload.toString(), now);
        auditRecordRepository.save(audit);

        log.info("submitted: idempotencyKey={} instructionId={} status={} version={}",
                instruction.getIdempotencyKey(), instruction.getId(),
                instruction.getStatus(), instruction.getVersion());
        return new SubmitResult(instruction.getId(), instruction.getIdempotencyKey(),
                instruction.getStatus(), instruction.getVersion(), false);
    }

    /**
     * 乐观并发推进：显式校验 expectedVersion，状态迁移与审计记录同事务落库。
     * 版本不符或 flush 时乐观锁失败均抛出 OptimisticConflictException，供调用方区分。
     */
    @Override
    public AdvanceResult advance(Long instructionId, long expectedVersion, SettlementStatus target) {
        try {
            return transactionTemplate.execute(tx -> doAdvance(instructionId, expectedVersion, target));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new OptimisticConflictException(instructionId, expectedVersion,
                    "Concurrent modification on instruction " + instructionId);
        }
    }

    private AdvanceResult doAdvance(Long instructionId, long expectedVersion, SettlementStatus target) {
        SettlementInstruction instruction = instructionRepository.findById(instructionId)
                .orElseThrow(() -> new IllegalArgumentException("Instruction not found: " + instructionId));

        if (instruction.getVersion() != expectedVersion) {
            throw new OptimisticConflictException(instructionId, expectedVersion,
                    "Version mismatch: expected=" + expectedVersion + " actual=" + instruction.getVersion());
        }
        SettlementStatus from = instruction.getStatus();
        if (!ALLOWED.get(from).contains(target)) {
            throw new IllegalTransitionException(from, target);
        }

        Instant now = Instant.now();
        instruction.transitionTo(target, now);
        instruction = instructionRepository.saveAndFlush(instruction);

        long nextSeq = auditHistoryReader.nextSeq(instructionId);
        ObjectNode payload = objectMapper.createObjectNode()
                .put("idempotencyKey", instruction.getIdempotencyKey())
                .put("fromStatus", from.name())
                .put("toStatus", target.name())
                .put("version", instruction.getVersion())
                .put("channel", "API")
                .put("businessType", "PAYMENT");
        AuditRecord audit = new AuditRecord(instructionId, nextSeq, from, target,
                "STATUS_CHANGED", AuditPayloadUpcaster.CURRENT_SCHEMA_VERSION, payload.toString(), now);
        auditRecordRepository.save(audit);

        log.info("advanced: idempotencyKey={} instructionId={} {} -> {} version={}",
                instruction.getIdempotencyKey(), instructionId, from, target, instruction.getVersion());
        return new AdvanceResult(instructionId, target, instruction.getVersion());
    }

    /**
     * 键集分页：cursor 为上一页最后一条审计记录 id；新写入 id 更大，持续写入不漏不重。
     */
    @Override
    public AuditPage auditPage(Long cursor, int size) {
        List<AuditRecord> records = auditRecordRepository.findPageAfter(cursor, PageRequest.of(0, size));
        Long nextCursor = records.size() < size ? null : records.get(records.size() - 1).getId();
        return new AuditPage(records, nextCursor);
    }
}
