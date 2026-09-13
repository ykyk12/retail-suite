package com.retailsuite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 上下文能起来是底线：这条测试会暴露依赖冲突、Mapper 扫描、SQL 初始化等装配问题。 */
@SpringBootTest
@ActiveProfiles("test")
class RetailSuiteApplicationTests {

    @Test
    void contextLoads() {
    }
}
