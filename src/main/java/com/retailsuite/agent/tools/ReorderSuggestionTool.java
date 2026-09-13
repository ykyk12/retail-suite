package com.retailsuite.agent.tools;

import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.steward.service.StewardAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补货建议：按"日均销量 × 覆盖天数 + 安全库存"给出建议补货量。
 *
 * 两个刻意的设计：
 * 1) **低库存商品即使近期没销量也要列出来**（阈值本身就代表门店想保底），
 *    否则会出现"店里快没货了，管家却说不用补"这种反直觉结论；
 * 2) 只给建议、不直接下单——要下单必须走 {@code draft_purchase_order}（写操作，人工确认后才入库）。
 *
 * 算法本身在 {@link StewardAnalysisService} 里（与巡检日报共用），这里只负责把它讲成人话。
 */
@Component
@RequiredArgsConstructor
public class ReorderSuggestionTool implements AgentTool {

    private final StewardAnalysisService analysisService;

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
        int coverDays = intArg(args.get("days"), StewardAnalysisService.DEFAULT_COVER_DAYS);
        List<StewardAnalysisService.ReorderSuggestion> suggestions = analysisService.reorderSuggestions(storeId, coverDays);

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal estimatedTotal = BigDecimal.ZERO;
        for (StewardAnalysisService.ReorderSuggestion suggestion : suggestions) {
            estimatedTotal = estimatedTotal.add(suggestion.estimatedAmount());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", suggestion.productId());
            row.put("productName", suggestion.productName());
            row.put("stock", suggestion.stock());
            row.put("threshold", suggestion.threshold());
            row.put("soldIn7d", suggestion.soldInWindow());
            row.put("dailySales", suggestion.dailySales());
            row.put("suggestQuantity", suggestion.suggestQuantity());
            row.put("unitCost", suggestion.unitCost());
            row.put("estimatedAmount", suggestion.estimatedAmount());
            rows.add(row);
        }
        estimatedTotal = estimatedTotal.setScale(2, RoundingMode.HALF_UP);

        StringBuilder sb = new StringBuilder();
        if (rows.isEmpty()) {
            sb.append("暂时不需要补货：没有商品低于预警阈值，也没有出现断货风险。");
        } else {
            sb.append("建议补货 ").append(rows.size()).append(" 个商品，预估金额 ")
                    .append(estimatedTotal).append(" 元（按进价估算）：");
            for (Map<String, Object> row : rows) {
                sb.append("\n· ").append(row.get("productName"))
                        .append("：当前库存 ").append(row.get("stock"))
                        .append("（阈值 ").append(row.get("threshold")).append("），近 ")
                        .append(StewardAnalysisService.SALES_WINDOW_DAYS).append(" 天卖 ")
                        .append(row.get("soldIn7d")).append(" 件")
                        .append("，建议补 ").append(row.get("suggestQuantity")).append(" 件")
                        .append("（约 ").append(row.get("estimatedAmount")).append(" 元）");
            }
            sb.append("\n需要的话我可以直接生成采购单草稿，你确认入库后库存才会增加。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("coverDays", coverDays);
        data.put("count", rows.size());
        data.put("estimatedAmount", estimatedTotal);
        data.put("items", rows);
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
