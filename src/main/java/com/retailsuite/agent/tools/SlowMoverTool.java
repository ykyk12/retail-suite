package com.retailsuite.agent.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.mapper.ReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 滞销商品：连续 N 天卖不动、但还压着库存的商品。
 * 判据用"最后一次售出时间"，比"区间内销量为 0"更准确（能区分"最近没卖"和"从来没卖过"）。
 */
@Component
@RequiredArgsConstructor
public class SlowMoverTool implements AgentTool {

    private static final int DEFAULT_DAYS = 30;

    private final ProductMapper productMapper;
    private final ReportMapper reportMapper;

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
        int days = intArg(args.get("days"), DEFAULT_DAYS);
        LocalDate today = LocalDate.now();
        LocalDateTime cutoff = today.minusDays(days).atStartOfDay();

        // 近 N 天有销售的商品（不在滞销之列）
        Set<Long> soldRecently = new LinkedHashSet<>();
        for (ReportDtos.TopProduct product : reportTopProducts(storeId, cutoff, today.plusDays(1).atStartOfDay())) {
            soldRecently.add(product.productId());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal tiedUp = BigDecimal.ZERO;
        for (Product product : productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStoreId, storeId)
                .eq(Product::getStatus, 1)
                .gt(Product::getStock, 0))) {
            if (soldRecently.contains(product.getId())) {
                continue;
            }
            LocalDateTime lastSaleAt = reportMapper.lastSaleAt(storeId, product.getId());
            long daysSinceLastSale = lastSaleAt == null ? -1
                    : ChronoUnit.DAYS.between(lastSaleAt.toLocalDate(), today);
            BigDecimal stockValue = (product.getPurchasePrice() == null ? BigDecimal.ZERO : product.getPurchasePrice())
                    .multiply(BigDecimal.valueOf(product.getStock())).setScale(2, RoundingMode.HALF_UP);
            tiedUp = tiedUp.add(stockValue);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", product.getId());
            row.put("productName", product.getName());
            row.put("stock", product.getStock());
            row.put("purchasePrice", product.getPurchasePrice());
            row.put("stockValue", stockValue);
            row.put("lastSaleDate", lastSaleAt == null ? null : lastSaleAt.toLocalDate().toString());
            row.put("daysSinceLastSale", daysSinceLastSale);
            rows.add(row);
        }

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

    private List<ReportDtos.TopProduct> reportTopProducts(Long storeId, LocalDateTime from, LocalDateTime to) {
        // ReportService#topProducts 只接受日期，这里直接调 mapper 以支持更精确的时间区间
        List<ReportDtos.TopProductRow> rows = reportMapper.topProducts(storeId, from, to, 500);
        List<ReportDtos.TopProduct> products = new ArrayList<>(rows.size());
        for (ReportDtos.TopProductRow row : rows) {
            BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
            BigDecimal cost = row.getCost() == null ? BigDecimal.ZERO : row.getCost();
            products.add(new ReportDtos.TopProduct(row.getProductId(), row.getProductName(),
                    row.getQuantity() == null ? 0 : row.getQuantity(), amount,
                    amount.subtract(cost).setScale(2, RoundingMode.HALF_UP)));
        }
        return products;
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
