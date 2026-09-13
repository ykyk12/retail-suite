package com.retailsuite.agent.tools;

import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.config.AppProperties;
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
 * 滞销商品：连续 N 天卖不动、但还压着库存的商品。
 * 判据用"最后一次售出时间"，比"区间内销量为 0"更准确（能区分"最近没卖"和"从来没卖过"）。
 *
 * 判定逻辑与巡检日报共用 {@link StewardAnalysisService}，两边结论必然一致。
 */
@Component
@RequiredArgsConstructor
public class SlowMoverTool implements AgentTool {

    private final StewardAnalysisService analysisService;
    private final AppProperties properties;

    @Override
    public String name() {
        return "slow_movers";
    }

    @Override
    public String description() {
        return "查滞销商品：连续 N 天没有卖出、且还压着库存的商品，含压货金额（按进价计算）";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("days", "滞销判定天数，默认 30（也可理解为“至少有这么久没卖出去”）");
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
        int days = intArg(args.get("days"), properties.getInventory().getSlowMovingDays());
        List<StewardAnalysisService.SlowMover> movers = analysisService.slowMovers(storeId, days);

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal tiedUp = BigDecimal.ZERO;
        for (StewardAnalysisService.SlowMover mover : movers) {
            tiedUp = tiedUp.add(mover.stockValue());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", mover.productId());
            row.put("productName", mover.productName());
            row.put("stock", mover.stock());
            row.put("purchasePrice", mover.purchasePrice());
            row.put("stockValue", mover.stockValue());
            row.put("lastSaleDate", mover.lastSaleDate() == null ? null : mover.lastSaleDate().toString());
            row.put("daysSinceLastSale", mover.daysSinceLastSale());
            rows.add(row);
        }
        tiedUp = tiedUp.setScale(2, RoundingMode.HALF_UP);

        StringBuilder sb = new StringBuilder();
        if (rows.isEmpty()) {
            sb.append("没有滞销商品：近 ").append(days).append(" 天内每个有库存的商品都有销售记录。");
        } else {
            sb.append("近 ").append(days).append(" 天没有卖出的商品共 ").append(rows.size())
                    .append(" 个，合计压货 ").append(tiedUp).append(" 元：");
            for (Map<String, Object> row : rows) {
                sb.append("\n· ").append(row.get("productName"))
                        .append("：库存 ").append(row.get("stock"))
                        .append("，压货 ").append(row.get("stockValue")).append(" 元");
                Object daysSince = row.get("daysSinceLastSale");
                if (daysSince != null && ((Number) daysSince).longValue() >= 0) {
                    sb.append("，已 ").append(daysSince).append(" 天没卖出");
                } else {
                    sb.append("，从未卖出过");
                }
            }
            sb.append("\n建议：做组合促销或降价清理，滞销商品不要再进货。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", days);
        data.put("count", rows.size());
        data.put("tiedUpAmount", tiedUp);
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
