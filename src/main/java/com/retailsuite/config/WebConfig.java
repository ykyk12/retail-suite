package com.retailsuite.config;

import com.retailsuite.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web 层装配：拦截器 + 跨域 + 密码编码器。 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 只拦 /api/**：静态资源与文档不参与鉴权，白名单逻辑集中在 JwtAuthFilter.isWhitelisted
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }

    /**
     * 开发期跨域：前端 dev server（Vite 默认 5173）直连后端。
     * 部署形态是同域（Nginx 同时提供前端静态文件与 /api 反向代理），因此不需要放宽到 "*"。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /** BCrypt 自带盐、可按强度升级，比 MD5 加盐更安全（面试常问"为什么不用 MD5"）。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
