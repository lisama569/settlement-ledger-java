package com.github.lisama569.settlementledgerjava.service.exception;

/**
 * 审计历史的数据性问题基类：与正常的业务失败（冲突、非法迁移）区分，
 * 调用方据此识别“历史链条/内容不可解析”这类数据问题。
 */
public abstract class AuditDataException extends RuntimeException {

    private final Long instructionId;

    protected AuditDataException(Long instructionId, String message) {
        super(message);
        this.instructionId = instructionId;
    }

    public Long getInstructionId() {
        return instructionId;
    }

    /** 稳定的错误码，便于调用方分类。 */
    public abstract String getErrorCode();
}
