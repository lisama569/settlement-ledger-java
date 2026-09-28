package com.github.lisama569.settlementledgerjava.service.exception;

/**
 * 审计 payload 无法解析为期望的结构（非 JSON、关键业务字段类型错误等），
 * 不能靠结构演进规则补齐。
 */
public class AuditPayloadUnparseableException extends AuditDataException {

    public AuditPayloadUnparseableException(Long instructionId, String message, Throwable cause) {
        super(instructionId, message);
        if (cause != null) {
            initCause(cause);
        }
    }

    @Override
    public String getErrorCode() {
        return "AUDIT_PAYLOAD_UNPARSEABLE";
    }
}
