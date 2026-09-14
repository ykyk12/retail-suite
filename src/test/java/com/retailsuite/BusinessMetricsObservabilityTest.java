package com.retailsuite;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务指标可观测性：登录成功后，Micrometer 里应能查到 app.login.success 计数器。
 * 这证明"埋点 → MeterRegistry → /actuator/metrics"这条链路是通的。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessMetricsObservabilityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void 登录成功后业务指标被记录() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk());

        Double count = meterRegistry.find("app.login.success").counter().count();
        assertNotNull(count, "应注册 app.login.success 计数器");
        assertTrue(count >= 1, "登录成功至少累计 1 次，实际=" + count);
    }

    @Test
    void 密码错误后失败指标被记录() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        Double count = meterRegistry.find("app.login.failure").counter().count();
        assertNotNull(count, "应注册 app.login.failure 计数器");
        assertTrue(count >= 1, "登录失败至少累计 1 次，实际=" + count);
    }
}
