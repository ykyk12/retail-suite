package com.retailsuite.agent.tools;

import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 销售排行：回答"哪个商品最好卖"——可按销售额、销量或毛利排序。 */
@Component
@RequiredArgsConstructor
public class SalesRankingTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 5;

    private final ReportService reportService;

    @Override
    public String name() {
        return "sales_ranking";
    }

    @Override
    public String description() {
        return "商品销售排行：哪个卖得最好。可按销售额 amount（默认）/ 销量 quantity / 毛利 grossProfit 排序，支持最近 N 天";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("metric", "排序口径：amount（销售额，默认）/ quantity（销量）/ grossProfit（毛利）");
        params.put("days", "统计最近多少天，默认 7");
        params.put("limit", "返回前几名，默认 5");
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
        String metric = args.get("metric") == null ? "amount" : String.valueOf(args.get("metric"));
        int days = intArg(args.get("days"), 7);
        int limit = intArg(args.get("limit"), DEFAULT_LIMIT);
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(Math.max(1, days) - 1L);

        List<ReportDtos.TopProduct> top = new ArrayList<>(reportService.topProducts(storeId, from, to, 50));
        if (top.isEmpty()) {
            return ToolOutcome.ok(from + " ~ " + to + " 没有销售记录", Map.of("from", from.toString(), "to", to.toString()));
        }
        Comparator<ReportDtos.TopProduct> comparator = switch (metric) {
            case "quantity" -> Comparator.comparingLong(ReportDtos.TopProduct::quantity).reversed();
            case "grossProfit" -> Comparator.comparing(ReportDtos.TopProduct::grossProfit).reversed();
            default -> Comparator.comparing(ReportDtos.TopProduct::amount).reversed();
        };
        top.sort(comparator);
        List<ReportDtos.TopProduct> selected = top.subList(0, Math.min(Math.max(limit, 1), top.size()));

        String metricText = switch (metric) {
            case "quantity" -> "销量";
            case "grossProfit" -> "毛利";
            default -> "销售额";
        };
        StringBuilder sb = new StringBuilder(from + " ~ " + to + " 按" + metricText + "排行：");
        int index = 1;
        for (ReportDtos.TopProduct product : selected) {
            sb.append("\n").append(index++).append(". ").append(product.productName())
                    .append("：").append(metricText).append(" ");
            if ("quantity".equals(metric)) {
                sb.append(product.quantity()).append(" 件");
            } else if ("grossProfit".equals(metric)) {
                sb.append(product.grossProfit()).append(" 元");
            } else {
                sb.append(product.amount()).append(" 元");
            }
            sb.append("（销量 ").append(product.quantity()).append(" 件，销售额 ").append(product.amount())
                    .append(" 元，毛利 ").append(product.grossProfit()).append(" 元）");
        }
        // 顺带给一句集中度，便于判断是否过度依赖单品
        BigDecimal totalAmount = top.stream().map(ReportDtos.TopProduct::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal topAmount = selected.stream().map(ReportDtos.TopProduct::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal share = topAmount.multiply(BigDecimal.valueOf(100))
                    .divide(totalAmount, 1, RoundingMode.HALF_UP);
            sb.append("\n前 ").append(selected.size()).append(" 名占这段时间销售额的 ").append(share).append("%");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metric", metric);
        data.put("from", from.toString());
        data.put("to", to.toString());
        data.put("items", selected.stream().map(product -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", product.productId());
            row.put("productName", product.productName());
            row.put("quantity", product.quantity());
            row.put("amount", product.amount());
            row.put("grossProfit", product.grossProfit());
            return row;
        }).toList());
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
}
