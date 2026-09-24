package com.github.lisama569.settlementledgerjava.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 审计事件负载。字段可演进，缺失字段在解码后由 {@link EventPayloads#normalize}
 * 按确定性默认值补齐（而非静默 null 或错列）。
 *
 * <p>版本历史：
 * <ul>
 *   <li>payload_version=1：SUBMITTED 无 channel 字段</li>
 *   <li>payload_version=2：SUBMITTED 新增 channel 字段</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventPayload(
        @JsonProperty("instructionId") String instructionId,
        @JsonProperty("debtorAccount") String debtorAccount,
        @JsonProperty("creditorAccount") String creditorAccount,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("submittedBy") String submittedBy,
        @JsonProperty("channel") String channel,
        @JsonProperty("fromStatus") SettlementStatus fromStatus,
        @JsonProperty("toStatus") SettlementStatus toStatus,
        @JsonProperty("reason") String reason
) {
    public static EventPayload submitted(String instructionId, String debtorAccount, String creditorAccount,
                                         BigDecimal amount, String currency, String submittedBy, String channel) {
        return new EventPayload(instructionId, debtorAccount, creditorAccount, amount, currency,
                submittedBy, channel, null, null, null);
    }

    public static EventPayload statusChanged(String instructionId, SettlementStatus fromStatus,
                                             SettlementStatus toStatus, String reason) {
        return new EventPayload(instructionId, null, null, null, null,
                null, null, fromStatus, toStatus, reason);
    }
}
