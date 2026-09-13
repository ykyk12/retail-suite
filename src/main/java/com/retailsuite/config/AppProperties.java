package com.retailsuite.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** app.* 业务配置集中在这里，避免魔法数字散落在业务代码里。 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Inventory inventory = new Inventory();
    private Order order = new Order();
    private Report report = new Report();
    private Ai ai = new Ai();

    @Data
    public static class Jwt {
        private String secret = "retail-suite-dev-secret-please-override-in-prod-32bytes";
        private int accessTokenMinutes = 120;
        private String issuer = "retail-suite";
    }

    @Data
    public static class Inventory {
        private int defaultLowStockThreshold = 10;
        /** 临期预警阈值（天）：到期日在 N 天内视为临期 */
        private int expiryAlertDays = 30;
        /** 滞销判定天数：连续 N 天没有销售视为滞销（管家巡检用） */
        private int slowMovingDays = 30;
    }

    @Data
    public static class Order {
        private int maxItemsPerOrder = 200;
        private int idempotencyRetentionDays = 7;
    }

    @Data
    public static class Report {
        private String dailySummaryCron = "0 10 0 * * ?";
    }

    @Data
    public static class Ai {
        private boolean enabled = true;
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private int timeoutSeconds = 60;
        /** 自然语言录入的草稿必须人工确认才落库；留着它是为了说明"这个值不允许被配成 false"。 */
        private boolean requireHumanConfirm = true;

        public boolean llmConfigured() {
            return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank();
        }
    }
}
