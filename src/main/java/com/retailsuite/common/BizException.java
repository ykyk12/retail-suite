package com.retailsuite.common;

/** 业务异常：由全局异常处理器统一转成 ApiResponse。 */
public class BizException extends RuntimeException {

    private final ErrorCode code;

    public BizException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static BizException notFound(String what) {
        return new BizException(ErrorCode.NOT_FOUND, what + "不存在");
    }

    public ErrorCode getCode() {
        return code;
    }
}
