package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.EventPage;
import com.github.lisama569.settlementledgerjava.domain.EventPayload;
import com.github.lisama569.settlementledgerjava.domain.EventType;
import com.github.lisama569.settlementledgerjava.domain.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.domain.SettlementEvent;
import com.github.lisama569.settlementledgerjava.domain.SettlementInstruction;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.domain.SubmitCommand;
import com.github.lisama569.settlementledgerjava.domain.SubmitResult;
import com.github.lisama569.settlementledgerjava.domain.TransitionCommand;
import com.github.lisama569.settlementledgerjava.error.ErrorCode;
import com.github.lisama569.settlementledgerjava.error.IdempotencyConflictException;
import com.github.lisama569.settlementledgerjava.error.OptimisticLockConflictException;
import com.github.lisama569.settlementledgerjava.error.SettlementException;
import com.github.lisama569.settlementledgerjava.repository.EventRepository;
import com.github.lisama569.settlementledgerjava.repository.IdempotencyRepository;
import com.github.lisama569.settlementledgerjava.repository.InstructionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    /** 合法状态推进关系；终态不可再推进。 */
    private static final Map<SettlementStatus, Set<SettlementStatus>> ALLOWED_TRANSITIONS = Map.of(
            SettlementStatus.ACCEPTED, Set.of(SettlementStatus.PROCESSING, SettlementStatus.REJECTED),
            SettlementStatus.PROCESSING, Set.of(SettlementStatus.SETTLED, SettlementStatus.REJECTED),
            SettlementStatus.SETTLED, Set.of(),
            SettlementStatus.REJECTED, Set.of());

    private final InstructionRepository instructionRepository;
    private final EventRepository eventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public SettlementService(InstructionRepository instructionRepository,
                             EventRepository eventRepository,
                             IdempotencyRepository idempotencyRepository) {
        this(instructionRepository, eventRepository, idempotencyRepository, Clock.systemUTC());
    }

    SettlementService(InstructionRepository instructionRepository,
                      EventRepository eventRepository,
                      IdempotencyRepository idempotencyRepository,
                      Clock clock) {
        this.instructionRepository = instructionRepository;
        this.eventRepository = eventRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.clock = clock;
    }

    /**
     * 受理结算指令。指令、首条审计事件与幂等记录在同一事务内原子落库。
     * 相同幂等键重复提交：请求指纹一致时返回首次结果（duplicated=true，可区分）；
     * 指纹不一致时以 IDEMPOTENCY_KEY_DUPLICATED_CONFLICT 拒绝；
     * 并发提交同键时由唯一约束保证恰好一次生效。
     */
    @Transactional
    public SubmitResult submit(SubmitCommand command) {
        validate(command);
        String key = command.idempotencyKey();
        String fingerprint = fingerprint(command);

        Optional<IdempotencyRepository.IdempotentRecord> existing = idempotencyRepository.findByKey(key);
        if (existing.isPresent()) {
            return handleDuplicate(key, fingerprint, existing.get());
        }

        Instant now = clock.instant();
        String channel = normalizeChannel(command.channel());
        SettlementInstruction instruction = new SettlementInstruction(
                command.instructionId(), command.debtorAccount(), command.creditorAccount(),
                command.amount(), command.currency(), channel,
                SettlementStatus.ACCEPTED, 1L, command.submittedBy(), null, now, now);

        boolean claimed = idempotencyRepository.insertIfAbsent(key, command.instructionId(),
                fingerprint, SettlementStatus.ACCEPTED);
        if (!claimed) {
            // 并发同键：另一事务已抢先登记，回退为重复提交语义
            IdempotencyRepository.IdempotentRecord winner = idempotencyRepository.findByKey(key).orElseThrow();
            log.info("submit lost idempotency race idempotencyKey={}", key);
            return handleDuplicate(key, fingerprint, winner);
        }
        instructionRepository.insert(instruction);
        eventRepository.append(new SettlementEvent(
                0L, command.instructionId(), EventType.SUBMITTED,
                EventPayloads.CURRENT_PAYLOAD_VERSION,
                EventPayloads.encode(EventPayload.submitted(
                        command.instructionId(), command.debtorAccount(), command.creditorAccount(),
                        command.amount(), command.currency(), command.submittedBy(), channel)),
                now));

        log.info("submit accepted idempotencyKey={} instructionId={} version={} status={} snapshot={}",
                key, instruction.instructionId(), instruction.version(), instruction.status(),
                toSnapshot(instruction));
        return new SubmitResult(instruction.instructionId(), instruction.status(), instruction.version(), false);
    }

    /**
     * 按乐观版本号推进状态。指令状态更新、审计事件追加与幂等状态联动在同一事务内，
     * CAS 更新 0 行即版本冲突（错误码 OPTIMISTIC_LOCK_CONFLICT，与业务非法流转可区分）。
     */
    @Transactional
    public InstructionSnapshot transition(TransitionCommand command) {
        SettlementInstruction current = instructionRepository.findById(command.instructionId())
                .orElseThrow(() -> new SettlementException(ErrorCode.INSTRUCTION_NOT_FOUND,
                        "instruction not found: " + command.instructionId()));

        if (current.version() != command.expectedVersion()) {
            log.info("transition optimistic conflict instructionId={} expectedVersion={} actualVersion={}",
                    command.instructionId(), command.expectedVersion(), current.version());
            throw new OptimisticLockConflictException(
                    command.instructionId(), command.expectedVersion(), current.version());
        }

        SettlementStatus target = command.targetStatus();
        Set<SettlementStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(current.status(), Set.of());
        if (!allowed.contains(target)) {
            throw new SettlementException(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                    "illegal transition " + current.status() + " -> " + target
                            + " for instruction " + command.instructionId());
        }
        if (target == SettlementStatus.REJECTED && (command.reason() == null || command.reason().isBlank())) {
            throw new SettlementException(ErrorCode.VALIDATION_FAILED,
                    "rejection reason is required for instruction " + command.instructionId());
        }

        Instant now = clock.instant();
        String reason = target == SettlementStatus.REJECTED ? command.reason() : null;
        SettlementInstruction next = new SettlementInstruction(
                current.instructionId(), current.debtorAccount(), current.creditorAccount(),
                current.amount(), current.currency(), current.channel(),
                target, current.version() + 1, current.submittedBy(), reason,
                current.createdAt(), now);

        int affected = instructionRepository.compareAndUpdateStatus(next, current.version());
        if (affected == 0) {
            // 与并发事务在同一基准版本上竞争：重读实际版本并报冲突
            long actualVersion = instructionRepository.findById(command.instructionId())
                    .map(SettlementInstruction::version).orElse(current.version());
            log.info("transition cas lost instructionId={} expectedVersion={} actualVersion={}",
                    command.instructionId(), command.expectedVersion(), actualVersion);
            throw new OptimisticLockConflictException(
                    command.instructionId(), command.expectedVersion(), actualVersion);
        }

        eventRepository.append(new SettlementEvent(
                0L, command.instructionId(), EventType.STATUS_CHANGED,
                EventPayloads.CURRENT_PAYLOAD_VERSION,
                EventPayloads.encode(EventPayload.statusChanged(
                        command.instructionId(), current.status(), target, reason)),
                now));
        idempotencyRepository.updateStatusByInstruction(command.instructionId(), target);

        InstructionSnapshot snapshot = toSnapshot(next);
        log.info("transition ok instructionId={} from={} to={} version={} snapshot={}",
                command.instructionId(), current.status(), target, next.version(), snapshot);
        return snapshot;
    }

    @Transactional(readOnly = true)
    public Optional<InstructionSnapshot> get(String instructionId) {
        return instructionRepository.findById(instructionId).map(this::toSnapshot);
    }

    /**
     * 全局游标键集分页。cursor 为空（首页）时以 0 为下界；
     * 返回条数等于 limit 时给出下一页游标（本页最后一条 seqNo），否则为 null。
     */
    @Transactional(readOnly = true)
    public EventPage queryEvents(Long cursor, int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new SettlementException(ErrorCode.VALIDATION_FAILED, "limit must be in (0,1000]");
        }
        long afterSeq = cursor == null ? 0L : cursor;
        var rows = eventRepository.findPage(afterSeq, limit + 1);
        boolean hasMore = rows.size() > limit;
        var page = hasMore ? rows.subList(0, limit) : rows;
        Long nextCursor = hasMore ? page.get(page.size() - 1).seqNo() : null;
        return new EventPage(java.util.List.copyOf(page), nextCursor);
    }

    private SubmitResult handleDuplicate(String key, String fingerprint,
                                         IdempotencyRepository.IdempotentRecord record) {
        if (!record.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(key, record.instructionId(), record.status());
        }
        SettlementInstruction first = instructionRepository.findById(record.instructionId()).orElseThrow();
        log.info("submit idempotent hit idempotencyKey={} instructionId={} version={} status={} snapshot={}",
                key, first.instructionId(), first.version(), first.status(), toSnapshot(first));
        return new SubmitResult(first.instructionId(), first.status(), first.version(), true);
    }

    private void validate(SubmitCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new SettlementException(ErrorCode.VALIDATION_FAILED, "idempotencyKey is required");
        }
        if (command.instructionId() == null || command.instructionId().isBlank()) {
            throw new SettlementException(ErrorCode.VALIDATION_FAILED, "instructionId is required");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new SettlementException(ErrorCode.VALIDATION_FAILED, "amount must be positive");
        }
        if (command.amount().stripTrailingZeros().scale() > 2) {
            throw new SettlementException(ErrorCode.VALIDATION_FAILED, "amount scale must be <= 2");
        }
        if (command.currency() == null || command.currency().isBlank()) {
            throw new SettlementException(ErrorCode.VALIDATION_FAILED, "currency is required");
        }
        if (command.debtorAccount() == null || command.debtorAccount().isBlank()
                || command.creditorAccount() == null || command.creditorAccount().isBlank()) {
            throw new SettlementException(ErrorCode.VALIDATION_FAILED, "accounts are required");
        }
        if (command.debtorAccount().equals(command.creditorAccount())) {
            throw new SettlementException(ErrorCode.VALIDATION_FAILED, "debtor and creditor must differ");
        }
        if (command.submittedBy() == null || command.submittedBy().isBlank()) {
            throw new SettlementException(ErrorCode.VALIDATION_FAILED, "submittedBy is required");
        }
    }

    private static String normalizeChannel(String channel) {
        return (channel == null || channel.isBlank()) ? EventPayloads.DEFAULT_CHANNEL : channel.trim();
    }

    private InstructionSnapshot toSnapshot(SettlementInstruction i) {
        return new InstructionSnapshot(i.instructionId(), i.status(), i.version(),
                i.debtorAccount(), i.creditorAccount(), i.amount(), i.currency(),
                i.channel(), i.rejectionReason());
    }

    /** 请求指纹：幂等键相同但业务内容不同的请求可被识别并拒绝。 */
    static String fingerprint(SubmitCommand c) {
        String raw = String.join("|",
                c.instructionId(), c.debtorAccount(), c.creditorAccount(),
                c.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                c.currency(), normalizeChannel(c.channel()), c.submittedBy());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
