package com.retailsuite.security;

import com.retailsuite.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败限流：同一用户名连续失败 N 次后，临时锁定 M 分钟，防在线爆破 / 撞库。
 *
 * 为什么用 Redis 而不是内存 Map：
 *  多门店/多实例部署下，各进程内存计数互不共享，挡不住"分布式撞库"；
 *  Redis 的 INCR + 过期是天然的分布式计数窗口。这也让项目里此前"声明却没真正用过"的
 *  spring-data-redis 承担起第一个真实职责。
 *
 * 为什么允许降级（fail-open）：
 *  限流是"防叠加"的加固项，不是登录主链路。Redis 故障时**绝不能把正常用户挡在门外**，
 *  所以连不上就放行 + 告警——主流程可用性优先。这正是"缓存降级"思想的应用，
 *  也是面试里要能讲清的取舍：安全加固与可用性冲突时，先保可用，再用日志暴露隐患。
 *
 * 窗口模型：每次失败 INCR 一次并把 TTL 续到 blockSeconds（滑动窗口）；
 *  连续失败会一直把锁续住，停止尝试 blockSeconds 后键自动过期，自然解锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "login:fail:";

    private final StringRedisTemplate redis;
    private final AppProperties properties;

    /** 是否处于锁定期。Redis 不可用一律返回 false（放行）。 */
    public boolean isBlocked(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        try {
            String value = redis.opsForValue().get(key(username));
            int fails = value == null ? 0 : Integer.parseInt(value.trim());
            boolean blocked = fails >= properties.getSecurity().getLoginMaxFail();
            if (blocked) {
                log.warn("登录被限流拦截 username={} 失败次数={}", username, fails);
            }
            return blocked;
        } catch (RedisConnectionFailureException e) {
            log.warn("登录限流读取失败，按放行处理（降级）username={} err={}", username, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("登录限流读取异常，按放行处理 username={} err={}", username, e.toString());
            return false;
        }
    }

    /** 记录一次失败：计数 +1 并续期；Redis 故障时静默忽略。 */
    public void recordFailure(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().increment(key(username));
            redis.expire(key(username), Duration.ofSeconds(properties.getSecurity().getLoginBlockSeconds()));
        } catch (RedisConnectionFailureException e) {
            log.warn("登录限流计数失败，忽略（降级）username={} err={}", username, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("登录限流计数异常，忽略 username={} err={}", username, e.toString());
        }
    }

    /** 登录成功后清零计数，避免误伤正常用户。 */
    public void reset(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            redis.delete(key(username));
        } catch (RuntimeException e) {
            log.warn("登录限流清零失败，忽略 username={} err={}", username, e.toString());
        }
    }

    private String key(String username) {
        return KEY_PREFIX + username;
    }
}
