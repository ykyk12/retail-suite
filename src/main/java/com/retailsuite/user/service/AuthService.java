package com.retailsuite.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.audit.AuditService;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.config.AppProperties;
import com.retailsuite.security.AuthUser;
import com.retailsuite.security.JwtService;
import com.retailsuite.security.UserContext;
import com.retailsuite.user.dto.LoginRequest;
import com.retailsuite.user.dto.LoginResponse;
import com.retailsuite.user.entity.SysUser;
import com.retailsuite.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** 认证服务：校验密码 → 加载角色与权限 → 签发令牌 → 记录审计。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties properties;
    private final AuditService auditService;

    public LoginResponse login(LoginRequest request) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.username()));

        // 用户名不存在与密码错误返回同一句话：不泄漏"哪些用户名是存在的"
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.recordAnonymous(user == null ? null : user.getStoreId(), request.username(),
                    "LOGIN_FAILED", "用户名或密码错误");
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            auditService.recordAnonymous(user.getStoreId(), user.getUsername(), "LOGIN_FAILED", "账号已停用");
            throw new BizException(ErrorCode.FORBIDDEN, "账号已停用，请联系店长");
        }

        AuthUser authUser = loadAuthUser(user);
        String token = jwtService.issue(authUser);

        // 只更新登录时间：不整行读改写，避免并发登录把其他字段覆盖回旧值
        SysUser touch = new SysUser();
        touch.setId(user.getId());
        touch.setLastLoginAt(LocalDateTime.now());
        touch.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(touch);

        log.info("用户登录成功 username={} roles={} storeId={}", user.getUsername(), authUser.roleCodes(), user.getStoreId());
        return new LoginResponse(token, properties.getJwt().getAccessTokenMinutes() * 60L,
                new LoginResponse.UserView(user.getId(), user.getStoreId(), user.getUsername(), user.getRealName(),
                        authUser.roleCodes(), authUser.permissions()));
    }

    /** 当前登录用户信息（前端刷新页面后用它恢复用户态与权限）。 */
    public LoginResponse.UserView currentUser() {
        AuthUser user = UserContext.require();
        return new LoginResponse.UserView(user.userId(), user.storeId(), user.username(), user.realName(),
                user.roleCodes(), user.permissions());
    }

    public AuthUser loadAuthUser(SysUser user) {
        Set<String> roles = new LinkedHashSet<>(sysUserMapper.selectRoleCodes(user.getId()));
        Set<String> permissions = new LinkedHashSet<>(sysUserMapper.selectPermissionCodes(user.getId()));
        return new AuthUser(user.getId(), user.getStoreId(), user.getUsername(), user.getRealName(),
                Set.copyOf(roles), Set.copyOf(permissions));
    }
}
