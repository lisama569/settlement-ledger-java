package com.github.lisama569.settlementledgerjava.web;

import com.github.lisama569.settlementledgerjava.error.ErrorCode;
import com.github.lisama569.settlementledgerjava.error.OptimisticLockConflictException;
import com.github.lisama569.settlementledgerjava.error.SettlementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OptimisticLockConflictException.class)
    public ResponseEntity<ErrorResponse> handleOptimistic(OptimisticLockConflictException e) {
        log.warn("optimistic lock conflict instructionId={} expectedVersion={} actualVersion={}",
                e.getInstructionId(), e.getExpectedVersion(), e.getActualVersion());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.getErrorCode().name(),
                        String.format("version conflict: expected=%d actual=%d",
                                e.getExpectedVersion(), e.getActualVersion())));
    }

    @ExceptionHandler(SettlementException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(SettlementException e) {
        HttpStatus httpStatus = switch (e.getErrorCode()) {
            case INSTRUCTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_KEY_DUPLICATED_CONFLICT -> HttpStatus.CONFLICT;
            case ILLEGAL_STATUS_TRANSITION, VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case OPTIMISTIC_LOCK_CONFLICT -> HttpStatus.CONFLICT;
        };
        log.warn("business rejection errorCode={} message={}", e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(httpStatus)
                .body(new ErrorResponse(e.getErrorCode().name(), e.getMessage()));
    }
}
