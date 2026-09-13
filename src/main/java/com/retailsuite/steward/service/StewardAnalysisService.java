package com.retailsuite.steward.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.mapper.ReportMapper;
import com.retailsuite.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
 * 经营分析的**唯一口径**：补货建议、毛利异常、滞销判定三个算法只在这里实现一次。
 *
 * 为什么单独抽出来：同一件事会被两个入口问——店长直接问 Agent（工具路径），
 * 以及每天凌晨的巡检日报（Job 路径）。如果各写一份，早晚出现
 * "管家对话说补 5 件、日报说补 8 件"这种自己打自己的情况。
 * 工具与巡检都调这里，数字天然一致（这也是 M2 里两套意图识别漂移踩过的坑）。
 */
@Service
@RequiredArgsConstructor
public class StewardAnalysisService {

    /** 销量观察窗口（天）：短窗口对门店更灵敏，也避免被一个月前的活动干扰 */
    public static final int SALES_WINDOW_DAYS = 7;
    /** 默认覆盖天数：按这些天的销量备货 */
    public static final int DEFAULT_COVER_DAYS = 7;

    private final InventoryService inventoryService;
    private final ReportService reportService;
    private final ReportMapper reportMapper;
    private final ProductMapper productMapper;

    /** 补货建议行。 */
    public record ReorderSuggestion(Long productId,
                                    String productName,
                                    int stock,
                                    int threshold,
                                    long soldInWindow,
                                    BigDecimal dailySales,
                                    int suggestQuantity,
                                    BigDecimal unitCost,
                                    BigDecimal estimatedAmount) {
    }

    /** 毛利异常行。 */
    public record MarginAnomaly(Long productId,
                                String productName,
                                long soldQuantity,
                                BigDecimal soldAmount,
                                BigDecimal grossProfit,
                                BigDecimal marginPercent) {
    }

    /** 滞销行（{@code daysSinceLastSale < 0} 表示从未卖出）。 */
    public record SlowMover(Long productId,
                            String productName,
                            int stock,
                            BigDecimal purchasePrice,
                            BigDecimal stockValue,
                            LocalDate lastSaleDate,
                            long daysSinceLastSale) {
    }

    /**
     * 补货建议。
     *
     * 两条口径：
     * - 按近期日均销量备 {@code coverDays} 天，再加安全库存（阈值）；
     * - **没有销量但低于阈值的商品也要列出**（阈值本身就代表门店想保底），
     *   否则会出现"货架快空了，管家却说不用补"这种反直觉结论。
     */
    public List<ReorderSuggestion> reorderSuggestions(Long storeId, Integer coverDaysOverride) {
        int coverDays = coverDaysOverride == null || coverDaysOverride <= 0 ? DEFAULT_COVER_DAYS : coverDaysOverride;
        LocalDate today = LocalDate.now();
        List<ReportDtos.TopProduct> sold = reportService.topProducts(storeId,
                today.minusDays(SALES_WINDOW_DAYS - 1L), today, 500);
        Map<Long, Long> soldByProduct = new LinkedHashMap<>();
        for (ReportDtos.TopProduct product : sold) {
            soldByProduct.put(product.productId(), product.quantity());
        }

        List<ReorderSuggestion> suggestions = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (InventoryDtos.LowStockItem item : inventoryService.lowStockItems(storeId)) {
            if (!seen.add(item.productId())) {
                continue;
            }
            long soldQty = soldByProduct.getOrDefault(item.productId(), 0L);
            BigDecimal dailySales = BigDecimal.valueOf(soldQty)
                    .divide(BigDecimal.valueOf(SALES_WINDOW_DAYS), 2, RoundingMode.HALF_UP);
            int target = dailySales.compareTo(BigDecimal.ZERO) == 0
                    ? item.lowStockThreshold() * 2
                    : dailySales.multiply(BigDecimal.valueOf(coverDays)).setScale(0, RoundingMode.CEILING).intValue()
                            + item.lowStockThreshold();
            int suggest = Math.max(0, target - item.stock());
            if (suggest <= 0) {
                continue;
            }
            Product product = inventoryService.requireProduct(storeId, item.productId());
            BigDecimal unitCost = product.getPurchasePrice() == null ? BigDecimal.ZERO : product.getPurchasePrice();
            BigDecimal amount = unitCost.multiply(BigDecimal.valueOf(suggest)).setScale(2, RoundingMode.HALF_UP);
            suggestions.add(new ReorderSuggestion(item.productId(), item.name(), item.stock(),
                    item.lowStockThreshold(), soldQty, dailySales, suggest, unitCost, amount));
        }
        // 按预估金额降序：报告只保留前 N 条时，留下的是"最花钱"的那几条
        suggestions.sort((a, b) -> b.estimatedAmount().compareTo(a.estimatedAmount()));
        return suggestions;
    }

    /**
     * 毛利异常：近 {@code days} 天有销售、但毛利率低于阈值（或毛利为负）的商品。
     *
     * 只看"有销量"的商品——没卖出去的商品毛利无从谈起（那是滞销问题，见 {@link #slowMovers}）。
     * 毛利为负单独说明：那通常意味着售价填错或进价涨了还在按旧价卖，属亏损经营。
     */
    public List<MarginAnomaly> marginAnomalies(Long storeId, Integer daysOverride, BigDecimal minMarginPercent) {
        int days = daysOverride == null || daysOverride <= 0 ? 30 : daysOverride;
        BigDecimal floor = minMarginPercent == null ? BigDecimal.TEN : minMarginPercent;
        LocalDate today = LocalDate.now();
        List<ReportDtos.TopProduct> sold = reportService.topProducts(storeId,
                today.minusDays(days - 1L), today, 500);

        List<MarginAnomaly> anomalies = new ArrayList<>();
        for (ReportDtos.TopProduct product : sold) {
            BigDecimal amount = product.amount() == null ? BigDecimal.ZERO : product.amount();
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal profit = product.grossProfit() == null ? BigDecimal.ZERO : product.grossProfit();
            BigDecimal marginPercent = profit.multiply(BigDecimal.valueOf(100))
                    .divide(amount, 1, RoundingMode.HALF_UP);
            if (marginPercent.compareTo(floor) < 0) {
                anomalies.add(new MarginAnomaly(product.productId(), product.productName(), product.quantity(),
                        amount.setScale(2, RoundingMode.HALF_UP), profit, marginPercent));
            }
        }
        // 毛利率升序：负毛利排最前（它比"毛利偏低"更急）
        anomalies.sort((a, b) -> a.marginPercent().compareTo(b.marginPercent()));
        return anomalies;
    }

    /**
     * 滞销：连续 {@code days} 天没有销售、且仍压着库存的在售商品。
     *
     * 判据用"最后一次售出时间"而不是"区间内销量为 0"：
     * 后者会把"从来没卖过的新品"和"以前卖过、最近卖不动"混为一谈。
     */
    public List<SlowMover> slowMovers(Long storeId, Integer daysOverride) {
        int days = daysOverride == null || daysOverride <= 0 ? 30 : daysOverride;
        LocalDate today = LocalDate.now();
        LocalDateTime cutoff = today.minusDays(days).atStartOfDay();
        Set<Long> soldRecently = new LinkedHashSet<>();
        for (ReportDtos.TopProductRow row : reportMapper.topProducts(storeId, cutoff,
                today.plusDays(1).atStartOfDay(), 500)) {
            soldRecently.add(row.getProductId());
        }

        List<SlowMover> movers = new ArrayList<>();
        for (Product product : productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStoreId, storeId)
                .eq(Product::getStatus, 1)
                .gt(Product::getStock, 0))) {
            if (soldRecently.contains(product.getId())) {
                continue;
            }
            LocalDateTime lastSaleAt = reportMapper.lastSaleAt(storeId, product.getId());
            long daysSinceLastSale = lastSaleAt == null ? -1 : ChronoUnit.DAYS.between(lastSaleAt.toLocalDate(), today);
            BigDecimal purchasePrice = product.getPurchasePrice() == null ? BigDecimal.ZERO : product.getPurchasePrice();
            BigDecimal stockValue = purchasePrice.multiply(BigDecimal.valueOf(product.getStock()))
                    .setScale(2, RoundingMode.HALF_UP);
            movers.add(new SlowMover(product.getId(), product.getName(), product.getStock(), purchasePrice,
                    stockValue, lastSaleAt == null ? null : lastSaleAt.toLocalDate(), daysSinceLastSale));
        }
        // 压货金额降序：报告先讲"占钱最多"的滞销品
        movers.sort((a, b) -> b.stockValue().compareTo(a.stockValue()));
        return movers;
    }
}
