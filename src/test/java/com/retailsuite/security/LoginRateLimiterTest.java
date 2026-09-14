package com.retailsuite.security;

import com.retailsuite.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.net.SocketException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 登录限流的纯单元测试：用 Mockito 顶替 StringRedisTemplate，不依赖真实 Redis。
 * 重点验证三件事：未达阈值放行、达阈值锁定、以及 Redis 故障时 fail-open 不抛异常。
 */
class LoginRateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private AppProperties properties;
    private LoginRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        properties = new AppProperties();
        properties.getSecurity().setLoginMaxFail(3);
        properties.getSecurity().setLoginBlockSeconds(300);
        limiter = new LoginRateLimiter(redis, properties);
    }

    @Test
    void 无失败记录时不拦截() {
        when(ops.get("login:fail:alice")).thenReturn(null);
        assertThat(limiter.isBlocked("alice")).isFalse();
    }

    @Test
    void 失败次数达到阈值后被锁定() {
        when(ops.get("login:fail:bob")).thenReturn("3");
        assertThat(limiter.isBlocked("bob")).isTrue();
    }

    @Test
    void 记录失败会自增并续期() {
        limiter.recordFailure("carol");
        verify(ops).increment("login:fail:carol");
        verify(redis).expire(eq("login:fail:carol"), eq(Duration.ofSeconds(300)));
    }

    @Test
    void 成功后清零计数() {
        limiter.reset("carol");
        verify(redis).delete("login:fail:carol");
    }

    @Test
    void Redis故障时读取降级为放行且不抛异常() {
        when(ops.get(anyString())).thenThrow(
                new RedisConnectionFailureException("down", new SocketException("refused")));
        // 关键：不能因为 Redis 挂了就把登录主流程带崩
        assertThat(limiter.isBlocked("dave")).isFalse();
    }

    @Test
    void Redis故障时记录失败静默忽略() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(ops).increment(anyString());
        // 不能抛出
        limiter.recordFailure("dave");
    }
}
