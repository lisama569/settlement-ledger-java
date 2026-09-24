package com.github.lisama569.settlementledgerjava.error;

/**
 * 业务异常基类，携带可区分的错误码。
 */
public class SettlementException extends RuntimeException {
    private final ErrorCode errorCode;

    public SettlementException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
