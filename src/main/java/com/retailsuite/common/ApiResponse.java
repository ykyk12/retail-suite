package com.retailsuite.common;

import org.slf4j.MDC;

/**
 * 统一响应体：前端只认这一种结构，异常也走同一形状。
 * 注意 record 的访问器必须 public，私有静态方法不能与组件同名（否则编译期直接报错）。
 */
public record ApiResponse<T>(boolean success, String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, ErrorCode.OK.name(), ErrorCode.OK.defaultMessage(), data, currentTraceId());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, ErrorCode.OK.name(), ErrorCode.OK.defaultMessage(), null, currentTraceId());
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message) {
        return new ApiResponse<>(false, code.name(),
                message == null ? code.defaultMessage() : message, null, currentTraceId());
    }

    private static String currentTraceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
