package com.retailsuite.security;

import java.util.Set;

/**
 * 当前登录用户（认证后的最小上下文）。
 * 放在 ThreadLocal 里由过滤器写入、请求结束时清理——这是本项目的"数据隔离源头"：
 * 所有查询都必须带 storeId，避免越权看别家门店的数据。
 */
public record AuthUser(Long userId,
                       Long storeId,
                       String username,
                       String realName,
                       Set<String> roleCodes,
                       Set<String> permissions) {

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public boolean isAdmin() {
        return roleCodes.contains("ADMIN");
    }
}
