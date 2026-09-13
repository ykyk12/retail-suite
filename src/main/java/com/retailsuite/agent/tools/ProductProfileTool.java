package com.retailsuite.agent.tools;

import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.common.BizException;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.service.ExpiryService;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.mapper.ReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单品画像：一个商品"值不值得继续卖"的全部依据——
 * 进价、售价、毛利率、当前库存、批次与到期日、近 30 天销量、最后一次售出距今多久。
 *
 * 这是管家最常用的工具：店长问"这个商品怎么样"，它就靠这一条把数据摆齐。
 */
@Component
@RequiredArgsConstructor
public class ProductProfileTool implements AgentTool {

    private static final int LOOKBACK_DAYS = 30;

    private final ProductService productService;
    private final ExpiryService expiryService;
    private final ReportMapper reportMapper;

    @Override
    public String name() {
        return "product_profile";
    }

    @Override
    public String description() {
        return "单品画像：查某个商品的进价、售价、毛利率、库存、批次与到期日、近 30 天销量、多久没卖出去。参数 keyword 必填（商品名或条码）";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keyword", "必填：商品名关键字或条码");
        return params;
    }

    @Override
    public String permission() {
        return "product:read";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolOutcome execute(Long storeId, Map<String, Object> args) {
        String keyword = String.valueOf(args.getOrDefault("keyword", "")).trim();
        if (keyword.isBlank()) {
            return ToolOutcome.fail("缺少必填参数 keyword（商品名或条码）");
        }
        ProductDtos.View product;
        try {
            product = productService.findByBarcode(storeId, keyword);
        } catch (BizException notBarcode) {
            var page = productService.page(storeId, keyword, null, null, 1, 5);
            if (page.records().isEmpty()) {
                return ToolOutcome.fail("没有找到匹配「" + keyword + "」的商品（可用条码或名称关键字）");
            }
            if (page.records().size() > 1) {
                StringBuilder sb = new StringBuilder("匹配到多个商品，请指明是哪一个：");
                page.records().forEach(item -> sb.append("\n· ").append(item.name())
                        .append("（条码 ").append(item.barcode()).append("，库存 ").append(item.stock()).append("）"));
                return ToolOutcome.fail(sb.toString());
            }
            product = page.records().get(0);
        }

        LocalDate today = LocalDate.now();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        LocalDateTime from = today.minusDays(LOOKBACK_DAYS - 1L).atStartOfDay();
        ReportDtos.AggregateRow sold = reportMapper.salesOfProduct(storeId, product.id(), from, to);
        long soldQuantity = sold == null || sold.getItemCount() == null ? 0 : sold.getItemCount();
        BigDecimal soldAmount = sold == null || sold.getSalesAmount() == null ? BigDecimal.ZERO : sold.getSalesAmount();
        BigDecimal soldCost = sold == null || sold.getCostAmount() == null ? BigDecimal.ZERO : sold.getCostAmount();
        LocalDateTime lastSaleAt = reportMapper.lastSaleAt(storeId, product.id());

        BigDecimal salePrice = product.salePrice() == null ? BigDecimal.ZERO : product.salePrice();
        BigDecimal purchasePrice = product.purchasePrice() == null ? BigDecimal.ZERO : product.purchasePrice();
        BigDecimal grossMargin = salePrice.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : salePrice.subtract(purchasePrice).multiply(BigDecimal.valueOf(100))
                        .divide(salePrice, 1, RoundingMode.HALF_UP);

        List<InventoryDtos.BatchView> batches = expiryService.batchesOf(storeId, product.id());
        List<InventoryDtos.BatchView> available = batches.stream().filter(b -> b.quantity() > 0).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("「").append(product.name()).append("」（条码 ").append(product.barcode()).append("）\n")
                .append("· 进价 ").append(purchasePrice).append(" 元 / 售价 ").append(salePrice)
                .append(" 元，毛利率 ").append(grossMargin).append("%\n")
                .append("· 当前库存 ").append(product.stock()).append(" ").append(nullToEmpty(product.unit()))
                .append(product.lowStock() ? "（低于阈值 " + product.lowStockThreshold() + "，需要补货）" : "").append("\n");
        if (product.shelfLifeDays() != null) {
            sb.append("· 保质期 ").append(product.shelfLifeDays()).append(" 天\n");
        }
        if (available.isEmpty()) {
            sb.append("· 没有可用批次（库存可能来自旧数据，建议做一次盘点让库存落到批次上）\n");
        } else {
            sb.append("· 批次 ").append(available.size()).append(" 个：");
            for (InventoryDtos.BatchView batch : available) {
                sb.append("\n  - ").append(batch.batchNo())
                        .append(" 数量 ").append(batch.quantity());
                if (batch.expiryDate() != null) {
                    sb.append("，到期 ").append(batch.expiryDate()).append("（")
                            .append(batch.expired() ? "已过期" : "剩 " + batch.daysToExpiry() + " 天").append("）");
                } else {
                    sb.append("，无到期日");
                }
            }
            sb.append("\n");
        }
        sb.append("· 近 ").append(LOOKBACK_DAYS).append(" 天销量 ").append(soldQuantity).append(" 件")
                .append("，销售额 ").append(soldAmount).append(" 元");
        if (soldAmount.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("，毛利 ").append(soldAmount.subtract(soldCost)).append(" 元");
        }
        if (lastSaleAt == null) {
            sb.append("\n· 最近 ").append(LOOKBACK_DAYS).append(" 天没有销售记录");
        } else {
            long daysSinceLastSale = ChronoUnit.DAYS.between(lastSaleAt.toLocalDate(), today);
            sb.append("\n· 最后一次售出：").append(lastSaleAt.toLocalDate())
                    .append("（距今 ").append(daysSinceLastSale).append(" 天）");
            if (daysSinceLastSale >= 30) {
                sb.append("，已属滞销，建议促销或停止进货");
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", product.id());
        data.put("productName", product.name());
        data.put("barcode", product.barcode());
        data.put("purchasePrice", purchasePrice);
        data.put("salePrice", salePrice);
        data.put("grossMarginPercent", grossMargin);
        data.put("stock", product.stock());
        data.put("lowStock", product.lowStock());
        data.put("shelfLifeDays", product.shelfLifeDays());
        data.put("soldQuantity30d", soldQuantity);
        data.put("soldAmount30d", soldAmount);
        data.put("lastSaleAt", lastSaleAt == null ? null : lastSaleAt.toLocalDate().toString());
        data.put("batches", available.stream().map(batch -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchNo", batch.batchNo());
            row.put("quantity", batch.quantity());
            row.put("expiryDate", batch.expiryDate() == null ? null : batch.expiryDate().toString());
            row.put("daysToExpiry", batch.daysToExpiry());
            row.put("expired", batch.expired());
            return row;
        }).toList());
        return ToolOutcome.ok(sb.toString(), data);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
