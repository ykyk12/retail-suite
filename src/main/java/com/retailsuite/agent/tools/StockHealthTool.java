package com.retailsuite.agent.tools;

import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存健康：把"库存够不够卖"算清楚。
 *
 * 判据：近 7 天日均销量 × 补货周期（默认 3 天）+ 安全库存，与当前库存比较；
 * 同时把低于阈值的商品一并列出（即使近期没销量，阈值本身也说明门店想保底）。
 */
@Component
@RequiredArgsConstructor
public class StockHealthTool implements AgentTool {

    private static final int SALES_WINDOW_DAYS = 7;
    private static final int REPLENISH_CYCLE_DAYS = 3;

    private final InventoryService inventoryService;
    private final ReportService reportService;

    @Override
    public String name() {
        return "stock_health";
    }

    @Override
    public String description() {
        return "库存健康检查：哪些商品低于预警阈值、哪些有断货风险（按近期日均销量与补货周期估算还能卖几天）";
    }

    @Override
    public Map<String, String> parameterSchema() {
        return Map.of();
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of();
    }

    @Override
    public String permission() {
        return "inventory:read";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolOutcome execute(Long storeId, Map<String, Object> args) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(SALES_WINDOW_DAYS - 1L);
        List<ReportDtos.TopProduct> sold = reportService.topProducts(storeId, from, today, 200);
        Map<Long, Long> soldByProduct = new LinkedHashMap<>();
        for (ReportDtos.TopProduct product : sold) {
            soldByProduct.put(product.productId(), product.quantity());
        }

        List<InventoryDtos.LowStockItem> lowStock = inventoryService.lowStockItems(storeId);
        List<Map<String, Object>> risky = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        sb.append("近 ").append(SALES_WINDOW_DAYS).append(" 天的销量与当前库存对比（补货周期按 ")
                .append(REPLENISH_CYCLE_DAYS).append(" 天估算）：");
        for (InventoryDtos.LowStockItem item : lowStock) {
            long soldQty = soldByProduct.getOrDefault(item.productId(), 0L);
            BigDecimal dailySales = BigDecimal.valueOf(soldQty)
                    .divide(BigDecimal.valueOf(SALES_WINDOW_DAYS), 2, RoundingMode.HALF_UP);
            BigDecimal daysLeft = dailySales.compareTo(BigDecimal.ZERO) == 0
                    ? null
                    : BigDecimal.valueOf(item.stock()).divide(dailySales, 1, RoundingMode.HALF_UP);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", item.productId());
            row.put("productName", item.name());
            row.put("stock", item.stock());
            row.put("threshold", item.lowStockThreshold());
            row.put("soldIn7d", soldQty);
            row.put("dailySales", dailySales);
            row.put("daysOfCoverLeft", daysLeft);
            risky.add(row);

            sb.append("\n· ").append(item.name()).append("：库存 ").append(item.stock())
                    .append("（阈值 ").append(item.lowStockThreshold()).append("）");
            if (soldQty > 0) {
                sb.append("，近 ").append(SALES_WINDOW_DAYS).append(" 天卖了 ").append(soldQty)
                        .append(" 件，日均 ").append(dailySales).append(" 件");
                if (daysLeft != null && daysLeft.compareTo(BigDecimal.valueOf(REPLENISH_CYCLE_DAYS)) < 0) {
                    sb.append("，按当前速度只够卖 ").append(daysLeft).append(" 天——有断货风险，建议尽快补货");
                }
            } else {
                sb.append("，近 ").append(SALES_WINDOW_DAYS).append(" 天无销售（低于阈值，可按安全库存补一点）");
            }
        }
        if (lowStock.isEmpty()) {
            sb.append("\n目前没有低于预警阈值的商品，库存水位正常。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("salesWindowDays", SALES_WINDOW_DAYS);
        data.put("replenishCycleDays", REPLENISH_CYCLE_DAYS);
        data.put("riskyCount", risky.size());
        data.put("items", risky);
        return ToolOutcome.ok(sb.toString(), data);
    }
}
