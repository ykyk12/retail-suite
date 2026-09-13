package com.retailsuite.user.dto;

import java.util.Set;

/** 登录响应：令牌 + 过期秒数 + 用户信息（前端据此渲染菜单并做按钮级权限）。 */
public record LoginResponse(String token, long expiresInSeconds, UserView user) {

    /** 用户视图：**不含**密码哈希，也不含任何内部字段。 */
    public record UserView(Long id,
                           Long storeId,
                           String username,
                           String realName,
                           Set<String> roles,
                           Set<String> permissions) {
    }
}
