package com.github.lisama569.settlementledgerjava.service.exception;

/**
 * 归档过程本身的失败（区别于数据质量问题）。
 */
public class ArchiveException extends RuntimeException {
    public ArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
