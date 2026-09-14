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
    private Steward steward = new Steward();
    private Security security = new Security();

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

    /** 管家巡检（主动发现问题的定时任务 + 判定阈值）。 */
    @Data
    public static class Steward {
        /** 巡检任务执行时间：默认每天 07:30（开门前，店长到店就能看到今天的发现） */
        private String inspectCron = "0 30 7 * * ?";
        /** 毛利异常阈值（%）：近 30 天毛利率低于它就算异常 */
        private int marginAlertPercent = 10;
        /** 补货建议的备货覆盖天数 */
        private int coverDays = 7;
    }

    @Data
    public static class Security {
        /** 同一用户名连续登录失败达到该次数后临时锁定（防爆破/撞库）。 */
        private int loginMaxFail = 5;
        /** 锁定时长（秒）：连续失败期间滑动续期，停止尝试后自动过期解锁。 */
        private int loginBlockSeconds = 300;
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

        // ---- 管家 Agent 运行时 ----
        /** 单次对话最多几轮"模型 → 工具 → 模型" */
        private int agentMaxSteps = 4;
        /** 单个用户每分钟最多几次工具调用（防滥用与成本失控） */
        private int agentMaxToolCallsPerMinute = 30;
        /** 会话记忆保留多少轮 */
        private int sessionMaxTurns = 12;
        /** 会话闲置多久清理（分钟） */
        private int sessionTtlMinutes = 120;

        public boolean llmConfigured() {
            return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank();
        }
    }
}
