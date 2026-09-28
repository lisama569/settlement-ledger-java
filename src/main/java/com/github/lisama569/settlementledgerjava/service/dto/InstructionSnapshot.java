package com.github.lisama569.settlementledgerjava.service.dto;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 按指令重建出的完整业务快照（对账视图）：
 * 含受理时的业务属性、当前状态、版本号与最后一次变更时间。
 * attributes 承载结构演进过程中补齐/新增的业务属性，按 key 排序以保证导出确定性。
 */
public record InstructionSnapshot(Long instructionId,
                                  String idempotencyKey,
                                  BigDecimal amount,
                                  String currency,
                                  String payer,
                                  String payee,
                                  String channel,
                                  SettlementStatus status,
                                  long version,
                                  Instant lastChangedAt,
                                  Map<String, String> attributes) {
}
