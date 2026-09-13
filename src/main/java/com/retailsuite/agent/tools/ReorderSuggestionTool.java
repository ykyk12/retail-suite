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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * 补货建议：按"日均销量 × 覆盖天数 + 安全库存"给出建议补货量。
 *
 * 两个刻意的设计：
 * 1) **低库存商品即使近期没销量也要列出来**（阈值本身就代表门店想保底），
 *    否则会出现"店里快没货了，管家却说不用补"这种反直觉结论；
 * 2) 只给建议、不直接下单——要下单必须走 {@code draft_purchase_order}（写操作，人工确认后才入库）。
 */
@Component
@RequiredArgsConstructor
public class ReorderSuggestionTool implements AgentTool {

    private static final int SALES_WINDOW_DAYS = 7;
    private static final int COVER_DAYS = 7;

    private final InventoryService inventoryService;
    private final ReportService reportService;

    @Override
    public String name() {
        return "reorder_suggestion";
    }

    @Override
    public String description() {
        return "补货建议：结合近 7 天销量与当前库存，给出每个商品建议补多少件、约多少钱";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("days", "覆盖天数（按这些天的销量备货），默认 7");
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
        int coverDays = intArg(args.get("days"), COVER_DAYS);
        LocalDate today = LocalDate.now();
        List<ReportDtos.TopProduct> sold = reportService.topProducts(storeId,
                today.minusDays(SALES_WINDOW_DAYS - 1L), today, 200);
        Map<Long, Long> soldByProduct = new LinkedHashMap<>();
        for (ReportDtos.TopProduct product : sold) {
            soldByProduct.put(product.productId(), product.quantity());
        }

        Map<Long, InventoryDtos.LowStockItem> candidates = new LinkedHashMap<>();
        for (InventoryDtos.LowStockItem item : inventoryService.lowStockItems(storeId)) {
            candidates.put(item.productId(), item);
        }

        List<Map<String, Object>> suggestions = new java.util.ArrayList<>();
        BigDecimal estimatedTotal = BigDecimal.ZERO;
        Set<Long> seen = new LinkedHashSet<>();

        for (InventoryDtos.LowStockItem item : candidates.values()) {
            long soldQty = soldByProduct.getOrDefault(item.productId(), 0L);
            BigDecimal dailySales = BigDecimal.valueOf(soldQty)
                    .divide(BigDecimal.valueOf(SALES_WINDOW_DAYS), 2, RoundingMode.HALF_UP);
            int target = dailySales.compareTo(BigDecimal.ZERO) == 0
                    ? item.lowStockThreshold() * 2                       // 没销量也要保底
                    : dailySales.multiply(BigDecimal.valueOf(coverDays)).setScale(0, RoundingMode.CEILING).intValue()
                            + item.lowStockThreshold();
            int suggest = Math.max(0, target - item.stock());
            if (suggest <= 0) {
                continue;
            }
            seen.add(item.productId());
            var product = inventoryService.requireProduct(storeId, item.productId());
            BigDecimal unitCost = product.getPurchasePrice() == null ? BigDecimal.ZERO : product.getPurchasePrice();
            BigDecimal amount = unitCost.multiply(BigDecimal.valueOf(suggest)).setScale(2, RoundingMode.HALF_UP);
            estimatedTotal = estimatedTotal.add(amount);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", item.productId());
            row.put("productName", item.name());
            row.put("stock", item.stock());
            row.put("soldIn7d", soldQty);
            row.put("dailySales", dailySales);
            row.put("suggestQuantity", suggest);
            row.put("unitCost", unitCost);
            row.put("estimatedAmount", amount);
            suggestions.add(row);
        }

        StringBuilder sb = new StringBuilder();
        if (suggestions.isEmpty()) {
            sb.append("暂时不需要补货：没有商品低于预警阈值，也没有出现断货风险。");
        } else {
            sb.append("建议补货 ").append(suggestions.size()).append(" 个商品，预估金额 ")
                    .append(estimatedTotal).append(" 元（按进价估算）：");
            for (Map<String, Object> row : suggestions) {
                sb.append("\n· ").append(row.get("productName"))
                        .append("：当前库存 ").append(row.get("stock"))
                        .append("，近 ").append(SALES_WINDOW_DAYS).append(" 天卖 ")
                        .append(row.get("soldIn7d")).append(" 件")
                        .append("，建议补 ").append(row.get("suggestQuantity")).append(" 件")
                        .append("（约 ").append(row.get("estimatedAmount")).append(" 元）");
            }
            sb.append("\n需要的话我可以直接生成采购单草稿，你确认入库后库存才会增加。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("coverDays", coverDays);
        data.put("count", suggestions.size());
        data.put("estimatedAmount", estimatedTotal);
        data.put("items", suggestions);
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
