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
import java.util.LinkedHashMap;
import java.util.Map;

/** 经营概况：营业额、订单数、退款、毛利、客单价（支持今天/昨天/本周/本月/近 7 天）。 */
@Component
@RequiredArgsConstructor
public class SalesSummaryTool implements AgentTool {

    private final ReportService reportService;

    @Override
    public String name() {
        return "sales_summary";
    }

    @Override
    public String description() {
        return "查经营概况：营业额、订单数、商品件数、退款、毛利、客单价。适用于“今天卖了多少”“本周营业额”";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("period", "时间范围：TODAY（默认）/ YESTERDAY / THIS_WEEK / THIS_MONTH / LAST_7_DAYS");
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
        String period = args.get("period") == null ? "TODAY" : String.valueOf(args.get("period")).toUpperCase();
        LocalDate today = LocalDate.now();
        LocalDate from;
        LocalDate to;
        switch (period) {
            case "YESTERDAY" -> {
                from = today.minusDays(1);
                to = from;
            }
            case "THIS_WEEK" -> {
                from = today.with(java.time.DayOfWeek.MONDAY);
                to = today;
            }
            case "THIS_MONTH" -> {
                from = today.withDayOfMonth(1);
                to = today;
            }
            case "LAST_7_DAYS" -> {
                from = today.minusDays(6);
                to = today;
            }
            default -> {
                from = today;
                to = today;
            }
        }

        long orderCount = 0;
        long itemCount = 0;
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal refund = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        // 单日直接用实时口径；多日逐天累加（汇总表可能还没跑，实时口径更可靠）
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            ReportDtos.Overview overview = reportService.overview(storeId, day);
            orderCount += overview.orderCount();
            itemCount += overview.itemCount();
            sales = sales.add(overview.salesAmount());
            refund = refund.add(overview.refundAmount());
            net = net.add(overview.netAmount());
            profit = profit.add(overview.grossProfit());
        }
        BigDecimal avgOrder = orderCount == 0 ? BigDecimal.ZERO
                : net.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP);

        String range = from.equals(to) ? from.toString() : from + " ~ " + to;
        String summary = range + " 营业额（净销售额）" + net + " 元；销售额 " + sales + " 元，退款 " + refund
                + " 元；订单 " + orderCount + " 笔，商品 " + itemCount + " 件；毛利 " + profit
                + " 元，客单价 " + avgOrder + " 元";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", from.toString());
        data.put("to", to.toString());
        data.put("netAmount", net);
        data.put("salesAmount", sales);
        data.put("refundAmount", refund);
        data.put("orderCount", orderCount);
        data.put("itemCount", itemCount);
        data.put("grossProfit", profit);
        data.put("avgOrderAmount", avgOrder);
        return ToolOutcome.ok(summary, data);
    }
}
