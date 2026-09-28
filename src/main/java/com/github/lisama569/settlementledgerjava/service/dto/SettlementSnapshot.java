package com.github.lisama569.settlementledgerjava.service.dto;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.SortedMap;

/**
 * 按指令重建出的完整业务快照（对账视图）。
 *
 * @param status       当前状态（来自实时行，权威）
 * @param version      当前版本号
 * @param lastChangedAt 最后一次变更时间
 * @param attributes   受理时记录的其它业务属性（按 key 排序，保证序列化确定性）
 */
public record SettlementSnapshot(Long instructionId,
                                 String idempotencyKey,
                                 BigDecimal amount,
                                 String currency,
                                 String payer,
                                 String payee,
                                 SettlementStatus status,
                                 long version,
                                 Instant lastChangedAt,
                                 Instant receivedAt,
                                 SortedMap<String, String> attributes) {
}
