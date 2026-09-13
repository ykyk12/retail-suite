package com.retailsuite.inventory.service;

import com.retailsuite.config.AppProperties;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.entity.ProductBatch;
import com.retailsuite.inventory.mapper.ProductBatchMapper;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保质期与临期管理。
 *
 * 三个业务口径（门店最关心"这批货还值多少钱"）：
 * - **临期**：有到期日、未过期、剩余天数 ≤ 阈值（默认 30 天）——要促销或优先售卖
 * - **过期**：已过到期日且仍有库存——必须下架报损，继续卖就是食品安全问题
 * - **金额**：按批次成本价计算（压货资金），不是按售价（售价只是预期收入）
 *
 * 注意批次数量与聚合库存的一致性由 {@code InventoryService.batchMismatches} 校验，
 * 这里只做展示，不修正数据——账不对要显式暴露，不能悄悄改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiryService {

    private final ProductBatchMapper batchMapper;
    private final ProductMapper productMapper;
    private final AppProperties properties;

    public int alertDays(Integer override) {
        return override == null || override <= 0 ? properties.getInventory().getExpiryAlertDays() : override;
    }

    /** 临期批次（近效期优先，便于安排促销顺序）。 */
    public List<InventoryDtos.BatchView> expiring(Long storeId, Integer daysOverride) {
        LocalDate today = LocalDate.now();
        int days = alertDays(daysOverride);
        List<ProductBatch> batches = batchMapper.selectExpiring(storeId, today, today.plusDays(days));
        return toViews(storeId, batches, today);
    }

    /** 已过期批次（仍有库存，需要立即下架报损）。 */
    public List<InventoryDtos.BatchView> expired(Long storeId) {
        LocalDate today = LocalDate.now();
        List<ProductBatch> batches = batchMapper.selectExpired(storeId, today);
        return toViews(storeId, batches, today);
    }

    /** 临期与过期汇总。 */
    public InventoryDtos.ExpirySummary summary(Long storeId, Integer daysOverride) {
        LocalDate today = LocalDate.now();
        int days = alertDays(daysOverride);
        List<ProductBatch> expiring = batchMapper.selectExpiring(storeId, today, today.plusDays(days));
        List<ProductBatch> expired = batchMapper.selectExpired(storeId, today);

        long expiringQty = expiring.stream().mapToLong(batch -> qty(batch)).sum();
        long expiredQty = expired.stream().mapToLong(batch -> qty(batch)).sum();
        return new InventoryDtos.ExpirySummary(days,
                expiring.size(), expiringQty, amount(expiring),
                expired.size(), expiredQty, amount(expired));
    }

    /** 单品批次列表（供商品详情页展示"这一批什么时候到期"）。 */
    public List<InventoryDtos.BatchView> batchesOf(Long storeId, Long productId) {
        LocalDate today = LocalDate.now();
        return toViews(storeId, batchMapper.listByProduct(storeId, productId), today);
    }

    private List<InventoryDtos.BatchView> toViews(Long storeId, List<ProductBatch> batches, LocalDate today) {
        if (batches.isEmpty()) {
            return List.of();
        }
        // 一次性把商品名取出来，避免逐行查库（批次列表在临期页可能有上百行）
        Map<Long, String> names = new HashMap<>();
        for (Product product : productMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                        .eq(Product::getStoreId, storeId))) {
            names.put(product.getId(), product.getName());
        }
        List<InventoryDtos.BatchView> views = new ArrayList<>(batches.size());
        for (ProductBatch batch : batches) {
            views.add(new InventoryDtos.BatchView(batch.getId(), batch.getBatchNo(), batch.getProductId(),
                    names.getOrDefault(batch.getProductId(), "(已删除商品)"),
                    batch.getProductionDate(), batch.getExpiryDate(), batch.getQuantity(), batch.getCostPrice(),
                    batch.getRemark(), batch.daysToExpiry(today), batch.expiredAt(today)));
        }
        return views;
    }

    private long qty(ProductBatch batch) {
        return batch.getQuantity() == null ? 0 : batch.getQuantity();
    }

    private BigDecimal amount(List<ProductBatch> batches) {
        BigDecimal total = BigDecimal.ZERO;
        for (ProductBatch batch : batches) {
            BigDecimal cost = batch.getCostPrice() == null ? BigDecimal.ZERO : batch.getCostPrice();
            total = total.add(cost.multiply(BigDecimal.valueOf(qty(batch))));
        }
        return total.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** 供管家 Agent 的工具使用：把临期清单压缩成一段可直接喂给模型的文本。 */
    public String expiringDigest(Long storeId, Integer daysOverride, int limit) {
        List<InventoryDtos.BatchView> batches = expiring(storeId, daysOverride);
        if (batches.isEmpty()) {
            return "近 " + alertDays(daysOverride) + " 天内没有临期批次";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertDays", alertDays(daysOverride));
        payload.put("count", batches.size());
        payload.put("items", batches.stream().limit(Math.max(limit, 1)).map(batch -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("product", batch.productName());
            item.put("batchNo", batch.batchNo());
            item.put("expiryDate", batch.expiryDate());
            item.put("daysToExpiry", batch.daysToExpiry());
            item.put("quantity", batch.quantity());
            item.put("costAmount", batch.costPrice() == null ? BigDecimal.ZERO
                    : batch.costPrice().multiply(BigDecimal.valueOf(batch.quantity() == null ? 0 : batch.quantity())));
            return item;
        }).toList());
        return payload.toString();
    }
}
