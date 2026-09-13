package com.retailsuite.security;

import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;

/** 登录用户上下文：过滤器写入，业务代码读取，请求结束必须清理（否则线程复用会串号）。 */
public final class UserContext {

    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(AuthUser user) {
        HOLDER.set(user);
    }

    public static AuthUser getOrNull() {
        return HOLDER.get();
    }

    /** 取当前用户；未登录直接抛 401（调用方不需要写判空）。 */
    public static AuthUser require() {
        AuthUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return user;
    }

    public static Long requireStoreId() {
        return require().storeId();
    }

    public static Long currentUserId() {
        AuthUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    public static String currentUsername() {
        AuthUser user = HOLDER.get();
        return user == null ? null : user.username();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
