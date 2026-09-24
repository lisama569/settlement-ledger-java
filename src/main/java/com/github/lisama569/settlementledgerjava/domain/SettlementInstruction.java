package com.github.lisama569.settlementledgerjava.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算指令实体。version 字段用于乐观并发控制。
 */
@Entity
@Table(name = "settlement_instruction",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key", columnNames = "idempotency_key"))
public class SettlementInstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SettlementStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "payer", nullable = false, length = 64)
    private String payer;

    @Column(name = "payee", nullable = false, length = 64)
    private String payee;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SettlementInstruction() {
    }

    public SettlementInstruction(String idempotencyKey, BigDecimal amount, String currency,
                                 String payer, String payee, Instant now) {
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.payer = payer;
        this.payee = payee;
        this.status = SettlementStatus.RECEIVED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public SettlementStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPayer() { return payer; }
    public String getPayee() { return payee; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void transitionTo(SettlementStatus target, Instant now) {
        this.status = target;
        this.updatedAt = now;
    }
}
