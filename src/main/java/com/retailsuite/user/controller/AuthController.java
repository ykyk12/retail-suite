package com.retailsuite.user.controller;

import com.retailsuite.audit.AuditService;
import com.retailsuite.common.ApiResponse;
import com.retailsuite.security.UserContext;
import com.retailsuite.user.dto.LoginRequest;
import com.retailsuite.user.dto.LoginResponse;
import com.retailsuite.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;

    @PostMapping("/login")
    @Operation(summary = "登录", description = "返回 JWT 令牌与用户权限，前端据此渲染菜单与按钮")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/me")
    @Operation(summary = "当前登录用户")
    public ApiResponse<LoginResponse.UserView> me() {
        return ApiResponse.ok(authService.currentUser());
    }

    /**
     * 退出登录。JWT 是无状态的，服务端不存会话，所以这里只记录审计，令牌由前端丢弃。
     * 如果业务要求"退出后令牌立即失效"，方案是把令牌版本号写进用户表（改密码/退出即 +1），
     * 或在 Redis 里维护黑名单——本项目为保持零依赖没上，属于已知边界。
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录（服务端只记审计；令牌由前端丢弃）")
    public ApiResponse<Void> logout() {
        auditService.record("LOGOUT", "user", String.valueOf(UserContext.currentUserId()), "用户主动退出");
        return ApiResponse.ok();
    }
}
