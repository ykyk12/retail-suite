package com.retailsuite.agent.tools;

import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.config.AppProperties;
import com.retailsuite.steward.service.StewardAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 毛利异常：近 N 天有销售、但毛利率低于阈值的商品。
 *
 * 为什么单独成一个工具（而不是并进"销售排行"）：
 * 排行回答"哪个卖得好"，这个回答"哪个**卖得多却不赚钱**"——后者才是店长要立刻动手的信号。
 * 负毛利单独点名：那通常是售价填错或进价涨了没调价，属于持续性亏损。
 *
 * 口径与巡检日报共用 {@link StewardAnalysisService}，避免"对话说 8%、日报说 12%"这种自相矛盾。
 */
@Component
@RequiredArgsConstructor
public class MarginAlertTool implements AgentTool {

    private static final int DEFAULT_DAYS = 30;

    private final StewardAnalysisService analysisService;
    private final AppProperties properties;

    @Override
    public String name() {
        return "margin_alert";
    }

    @Override
    public String description() {
        return "毛利异常：查近 N 天毛利率过低或为负（卖得多却不赚钱）的商品，含销售额与毛利金额";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("days", "统计天数，默认 30");
        params.put("thresholdPercent", "毛利率阈值（%），低于它就算异常，默认取门店配置");
        return params;
    }

    @Override
    public String permission() {
        return "report:read";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolOutcome execute(Long storeId, Map<String, Object> args) {
        int days = intArg(args.get("days"), DEFAULT_DAYS);
        BigDecimal threshold = decimalArg(args.get("thresholdPercent"),
                BigDecimal.valueOf(properties.getSteward().getMarginAlertPercent()));
        List<StewardAnalysisService.MarginAnomaly> anomalies = analysisService.marginAnomalies(storeId, days, threshold);

        List<Map<String, Object>> items = new ArrayList<>();
        boolean losing = false;
        StringBuilder sb = new StringBuilder();
        if (anomalies.isEmpty()) {
            sb.append("近 ").append(days).append(" 天没有毛利率低于 ").append(threshold)
                    .append("% 的商品，定价与进货成本目前是健康的。");
        } else {
            sb.append("近 ").append(days).append(" 天毛利率低于 ").append(threshold).append("% 的商品共 ")
                    .append(anomalies.size()).append(" 个：");
            for (StewardAnalysisService.MarginAnomaly anomaly : anomalies) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("productId", anomaly.productId());
                row.put("productName", anomaly.productName());
                row.put("soldQuantity", anomaly.soldQuantity());
                row.put("soldAmount", anomaly.soldAmount());
                row.put("grossProfit", anomaly.grossProfit());
                row.put("marginPercent", anomaly.marginPercent());
                items.add(row);

                if (anomaly.grossProfit().compareTo(BigDecimal.ZERO) < 0) {
                    losing = true;
                }
                sb.append("\n· ").append(anomaly.productName())
                        .append("：卖了 ").append(anomaly.soldAmount()).append(" 元（")
                        .append(anomaly.soldQuantity()).append(" 件），毛利 ").append(anomaly.grossProfit())
                        .append(" 元，毛利率 ").append(anomaly.marginPercent()).append("%");
                if (anomaly.grossProfit().compareTo(BigDecimal.ZERO) < 0) {
                    sb.append("——**负毛利，卖一件亏一件**，请立刻核对售价与进价");
                }
            }
            if (!losing) {
                sb.append("\n建议：复核这些商品的售价，或与供应商重谈进价；低毛利商品不适合做折扣主力。");
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", days);
        data.put("thresholdPercent", threshold);
        data.put("count", items.size());
        data.put("hasLoss", losing);
        data.put("items", items);
        return ToolOutcome.ok(sb.toString(), data);
    }

    private int intArg(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private BigDecimal decimalArg(Object raw, BigDecimal fallback) {
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return raw == null ? fallback : new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
