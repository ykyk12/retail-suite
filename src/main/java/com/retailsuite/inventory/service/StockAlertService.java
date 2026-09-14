package com.retailsuite.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.entity.StockAlert;
import com.retailsuite.inventory.mapper.StockAlertMapper;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 低库存预警工单的开单与闭环。
 *
 * 为什么是"工单"而不是每次都算一遍：
 * 低库存列表是**查询即算**的快照，看完就过去；工单则把"某商品缺货了"沉淀成一条待办，
 * 直到补货/盘点把问题真正解决才关闭，避免店长今天看到、明天又看到同样的问题却没跟进。
 *
 * 开单时机：出库（收银/报损）把库存砸到阈值以下时，由库存服务在同一事务内回调
 * {@link #onStockOutflow}。同一商品已挂着 OPEN 工单就不重复开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlertService {

    private final StockAlertMapper alertMapper;
    private final ProductMapper productMapper;
    private final com.retailsuite.metrics.BusinessMetrics metrics;

    /**
     * 出库后评估是否需要开单。
     * 与出库在同一事务内执行：库存已被扣减，这里读到的 product.stock 是最新值。
     *
     * @return 新开的工单 id；未开单（不缺货或已有 OPEN 单）返回 null
     */
    public Long onStockOutflow(Long storeId, Long productId) {
        Product product = productMapper.selectById(productId);
        // 商品已删 / 不属本店：不越权开单
        if (product == null || !storeId.equals(product.getStoreId())) {
            return null;
        }
        int stock = product.getStock() == null ? 0 : product.getStock();
        int threshold = product.getLowStockThreshold() == null ? 0 : product.getLowStockThreshold();
        if (stock > threshold) {
            return null; // 还没到预警线
        }
        // 同商品已有 OPEN 工单：不重复开（出库持商品行排他锁，同商品串行，不会竞态开出两条）
        Long openCount = alertMapper.selectCount(new LambdaQueryWrapper<StockAlert>()
                .eq(StockAlert::getStoreId, storeId)
                .eq(StockAlert::getProductId, productId)
                .eq(StockAlert::getStatus, StockAlert.STATUS_OPEN));
        if (openCount != null && openCount > 0) {
            return null;
        }
        StockAlert alert = new StockAlert();
        alert.setStoreId(storeId);
        alert.setProductId(productId);
        alert.setProductName(product.getName());
        alert.setStockAtAlert(stock);
        alert.setThreshold(threshold);
        alert.setStatus(StockAlert.STATUS_OPEN);
        LocalDateTime now = LocalDateTime.now();
        alert.setCreatedAt(now);
        alert.setUpdatedAt(now);
        alert.setDeleted(0);
        alertMapper.insert(alert);
        metrics.stockAlertOpened(storeId);
        log.warn("低库存预警工单已开 storeId={} productId={} stock={} threshold={} alertId={}",
                storeId, productId, stock, threshold, alert.getId());
        return alert.getId();
    }

    /** 待处理工单（OPEN），按触发时间升序（越早缺的越靠前）。 */
    public List<InventoryDtos.AlertView> listOpen(Long storeId) {
        List<StockAlert> alerts = alertMapper.selectList(new LambdaQueryWrapper<StockAlert>()
                .eq(StockAlert::getStoreId, storeId)
                .eq(StockAlert::getStatus, StockAlert.STATUS_OPEN)
                .orderByAsc(StockAlert::getCreatedAt));
        return toViews(alerts);
    }

    /** 全部工单（含已闭环），便于审计"发现过多少次缺货"。 */
    public List<InventoryDtos.AlertView> listAll(Long storeId) {
        List<StockAlert> alerts = alertMapper.selectList(new LambdaQueryWrapper<StockAlert>()
                .eq(StockAlert::getStoreId, storeId)
                .orderByDesc(StockAlert::getCreatedAt));
        return toViews(alerts);
    }

    /** 闭环：把 OPEN 工单标记为 RESOLVED。重复闭环报冲突。 */
    public InventoryDtos.AlertView resolve(Long storeId, Long alertId, String remark) {
        StockAlert alert = alertMapper.selectById(alertId);
        if (alert == null || !storeId.equals(alert.getStoreId())) {
            throw BizException.notFound("低库存预警工单");
        }
        if (!StockAlert.STATUS_OPEN.equals(alert.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "工单已闭环，无需重复处理");
        }
        StockAlert update = new StockAlert();
        update.setId(alertId);
        update.setStatus(StockAlert.STATUS_RESOLVED);
        update.setResolveRemark(remark);
        update.setResolvedBy(UserContext.currentUserId());
        update.setResolvedAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        alertMapper.updateById(update);
        alert.setStatus(StockAlert.STATUS_RESOLVED);
        alert.setResolveRemark(remark);
        alert.setResolvedAt(update.getResolvedAt());
        alert.setResolvedBy(update.getResolvedBy());
        alert.setUpdatedAt(update.getUpdatedAt());
        log.info("低库存预警工单闭环 alertId={} productId={} by={}", alertId, alert.getProductId(),
                UserContext.currentUsername());
        return toView(alert);
    }

    private List<InventoryDtos.AlertView> toViews(List<StockAlert> alerts) {
        List<InventoryDtos.AlertView> views = new ArrayList<>(alerts.size());
        for (StockAlert alert : alerts) {
            views.add(toView(alert));
        }
        return views;
    }

    private InventoryDtos.AlertView toView(StockAlert alert) {
        return new InventoryDtos.AlertView(alert.getId(), alert.getProductId(), alert.getProductName(),
                alert.getStockAtAlert(), alert.getThreshold(), alert.getStatus(), alert.getResolveRemark(),
                alert.getCreatedAt(), alert.getResolvedAt());
    }
}
