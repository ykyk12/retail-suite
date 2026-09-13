package com.retailsuite.security;

import com.retailsuite.common.BizException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 认证过滤器：解析 Bearer 令牌并写入 UserContext。
 *
 * 分工说明：过滤器只负责"解析身份"，**不做拦截判断**；
 * 是否放行由 AuthInterceptor 统一决定（白名单只有一处，避免两套规则打架）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String AUTH_ERROR_ATTRIBUTE = "authError";

    private static final List<String> AUTH_WHITELIST = List.of("/api/auth/login", "/error");
    private static final List<String> AUTH_WHITELIST_PREFIXES = List.of(
            "/actuator/health", "/actuator/info", "/h2-console", "/swagger-ui", "/v3/api-docs");

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                AuthUser user = jwtService.verify(header.substring(BEARER_PREFIX.length()).trim());
                UserContext.set(user);
                MDC.put("userId", String.valueOf(user.userId()));
            } catch (BizException e) {
                // 认证失败不在这里返回：交给 AuthInterceptor 统一转 401 JSON，保持响应体形状一致
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getMessage());
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程会被复用，必须清理，否则会串号（下一个请求可能读到上一个用户的身份）
            UserContext.clear();
            MDC.remove("userId");
        }
    }

    /** 无需登录即可访问的路径（登录接口、健康检查、接口文档、H2 控制台）。 */
    public static boolean isWhitelisted(String path) {
        if (path == null) {
            return false;
        }
        if (AUTH_WHITELIST.contains(path)) {
            return true;
        }
        return AUTH_WHITELIST_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
