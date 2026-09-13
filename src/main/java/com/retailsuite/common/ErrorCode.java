package com.retailsuite.common;

/** 统一业务错误码。 */
public enum ErrorCode {
    OK("成功"),
    BAD_REQUEST("请求参数不合法"),
    UNAUTHORIZED("未登录或登录已过期"),
    FORBIDDEN("没有权限执行该操作"),
    NOT_FOUND("数据不存在"),
    CONFLICT("状态冲突，请刷新后重试"),
    STOCK_NOT_ENOUGH("库存不足"),
    DUPLICATE_REQUEST("重复请求（幂等命中）"),
    INTERNAL_ERROR("服务内部错误");

    private final String defaultMessage;

    ErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
