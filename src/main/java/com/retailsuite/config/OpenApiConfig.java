package com.retailsuite.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 文档（/swagger-ui.html）：面试演示时可以直接让对方点接口。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI retailSuiteOpenApi() {
        return new OpenAPI().info(new Info()
                .title("云小店 API")
                .version("1.0.0")
                .description("小微零售进销存与收银系统：商品 / 库存 / 采购 / 收银 / 退货 / 报表 / 经营助手"));
    }
}
