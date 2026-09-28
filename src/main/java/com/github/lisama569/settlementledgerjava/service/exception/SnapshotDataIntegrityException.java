package com.github.lisama569.settlementledgerjava.service.exception;

/**
 * 快照重建/导出时遇到的数据质量问题：链条断裂、payload 无法解析、缺少受理事件等。
 * 不静默跳过，也不当作正常快照返回。
 */
public class SnapshotDataIntegrityException extends RuntimeException {

    public enum Code {
        INSTRUCTION_NOT_FOUND,
        NO_SUBMIT_EVENT,
        PAYLOAD_UNPARSEABLE,
        CHAIN_BROKEN,
        UNSUPPORTED_SCHEMA
    }

    private final Code code;
    private final Long instructionId;

    public SnapshotDataIntegrityException(Code code, Long instructionId, String message) {
        super(message);
        this.code = code;
        this.instructionId = instructionId;
    }

    public SnapshotDataIntegrityException(Code code, Long instructionId, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.instructionId = instructionId;
    }

    public Code getCode() {
        return code;
    }

    public Long getInstructionId() {
        return instructionId;
    }
}
