package com.retailsuite.security;

import com.retailsuite.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 认证与鉴权拦截器：所有 /api/** 默认必须登录，声明了 {@link RequiresPermission} 的接口再校验权限码。
 *
 * 为什么用拦截器而不是 AOP 或 Spring Security：
 * - 权限声明就在 Controller 上，拦截器直接读注解，链路短、无隐藏魔法（便于排查"为什么 403"）；
 * - 本项目只有一两百个接口、规则简单，引 Security 的过滤器链反而更难解释清楚。
 *   如果将来要做方法级复杂表达式、OAuth2、多认证方式，再上 Spring Security 是合理的演进。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS 预检请求不带令牌，必须放行，否则浏览器端所有请求都会失败
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (JwtAuthFilter.isWhitelisted(path)) {
            return true;
        }

        AuthUser user = UserContext.getOrNull();
        if (user == null) {
            Object authError = request.getAttribute("authError");
            String message = authError == null ? "未登录或登录已过期" : String.valueOf(authError);
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED, message);
            return false;
        }

        RequiresPermission required = resolveRequiredPermission(handler);
        if (required != null && !user.hasPermission(required.value())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN,
                    "缺少权限：" + required.value() + "（当前角色 " + user.roleCodes() + "）");
            return false;
        }
        return true;
    }

    private RequiresPermission resolveRequiredPermission(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return null;
        }
        RequiresPermission onMethod = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequiresPermission.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequiresPermission.class);
    }

    private void writeError(HttpServletResponse response, int status, ErrorCode code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String traceId = org.slf4j.MDC.get(com.retailsuite.common.TraceIdFilter.TRACE_ID);
        response.getWriter().write("{\"success\":false,\"code\":\"" + code.name() + "\",\"message\":\""
                + message.replace("\"", "'") + "\",\"traceId\":\"" + (traceId == null ? "" : traceId) + "\"}");
    }
}
