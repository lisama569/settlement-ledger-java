package com.github.lisama569.settlementledgerjava.service.exception;

/**
 * 历史链条不连续：缺少事件、seq 不连续或前后状态不衔接。
 */
public class AuditChainBrokenException extends AuditDataException {

    public AuditChainBrokenException(Long instructionId, String message) {
        super(instructionId, message);
    }

    @Override
    public String getErrorCode() {
        return "AUDIT_CHAIN_BROKEN";
    }
}
