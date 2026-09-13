package com.retailsuite.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** 全局异常处理：业务异常给出可读原因，未知异常只暴露类名（不泄漏堆栈与内部结构）。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        HttpStatus status = switch (e.getCode()) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT, DUPLICATE_REQUEST -> HttpStatus.CONFLICT;
            case STOCK_NOT_ENOUGH -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        log.warn("业务异常 code={} message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(status).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    /** @Valid 校验失败：把字段级错误拼成人能看懂的一句话。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception e) {
        BindException bind = e instanceof MethodArgumentNotValidException manv
                ? new BindException(manv.getBindingResult())
                : (BindException) e;
        String detail = bind.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + " " + messageOf(field))
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, e.getClass().getSimpleName()));
    }

    private String messageOf(FieldError field) {
        return field.getDefaultMessage() == null ? "不合法" : field.getDefaultMessage();
    }
}
