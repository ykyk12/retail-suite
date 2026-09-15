package com.retailsuite;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回归：本机 Redis 未运行（零依赖开发 / CI 常态）时，/actuator/health 必须返回 200 + UP。
 *
 * 背景（本轮真实踩到的 bug）：
 *  spring-boot-starter-data-redis 在 classpath 上，Spring Boot 默认装配 RedisHealthIndicator。
 *  6379 连不上时它报 DOWN，再把整体健康度聚合为 DOWN → /actuator/health 返回 503。
 *  但登录限流（LoginRateLimiter）是 fail-open 的"可选加固"项：Redis 挂了应用照样能登录、能服务，
 *  绝不能让一个非关键依赖把健康度判死、进而被 K8s / 负载均衡把实例摘流。
 *
 * 修法：application.yml 默认 management.health.redis.enabled=false（可用 REDIS_HEALTH_ENABLED=true 打开），
 *  生产 profile（application-prod.yml）里 Redis 是真依赖，再显式置 true。
 *
 * 这条测试在"没有 Redis"的环境下跑：只要健康端点还是 UP、且不出现 DOWN 的 redis 组件即通过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthEndpointDegradationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void Redis缺失时健康端点仍为UP() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void Redis健康探针被关闭不参与聚合() throws Exception {
        // 可选加固依赖不参与健康聚合：components 里不应再出现 redis 组件
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components.redis").doesNotExist());
    }
}
