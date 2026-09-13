package com.retailsuite.purchase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.retailsuite.audit.AuditService;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.common.Ids;
import com.retailsuite.common.PageResult;
import com.retailsuite.config.AppProperties;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.purchase.dto.PurchaseDtos;
import com.retailsuite.purchase.entity.PurchaseOrder;
import com.retailsuite.purchase.entity.PurchaseOrderItem;
import com.retailsuite.purchase.mapper.PurchaseOrderItemMapper;
import com.retailsuite.purchase.mapper.PurchaseOrderMapper;
import com.retailsuite.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购单服务：录单（草稿）→ 确认入库（增加库存 + 写流水）。
 *
 * 三个刻意设计：
 * 1) 录单不动库存：门店实际流程是"先登记进货，货到了再确认入库"，两者分开才不会账实不符；
 * 2) 确认用**状态机条件更新**（where status='DRAFT'），天然防止并发重复确认导致库存被加两次；
 * 3) 同一商品在一张单里出现多次会被**合并**，避免一张单里两条同商品明细把库存和金额算乱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderItemMapper itemMapper;
    private final ProductMapper productMapper;
    private final InventoryService inventoryService;
    private final AuditService auditService;
    private final AppProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public PurchaseDtos.View create(Long storeId, PurchaseDtos.CreateRequest request) {
        int maxItems = properties.getOrder().getMaxItemsPerOrder();
        if (request.items().size() > maxItems) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单张采购单最多 " + maxItems + " 行明细");
        }

        // 合并同商品明细（数量相加，单价取最后一次录入）
        Map<Long, PurchaseDtos.ItemRequest> merged = new LinkedHashMap<>();
        for (PurchaseDtos.ItemRequest item : request.items()) {
            PurchaseDtos.ItemRequest previous = merged.get(item.productId());
            merged.put(item.productId(), previous == null ? item
                    : new PurchaseDtos.ItemRequest(item.productId(),
                    previous.quantity() + item.quantity(), item.unitCost()));
        }

        PurchaseOrder order = new PurchaseOrder();
        order.setStoreId(storeId);
        order.setOrderNo(Ids.orderNo("PO"));
        order.setSupplierName(request.supplierName());
        order.setItemCount(merged.size());
        order.setStatus(PurchaseOrder.STATUS_DRAFT);
        order.setRemark(request.remark());
        order.setOperatorId(UserContext.currentUserId());
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        order.setDeleted(0);

        BigDecimal total = BigDecimal.ZERO;
        List<PurchaseOrderItem> items = new ArrayList<>(merged.size());
        for (PurchaseDtos.ItemRequest item : merged.values()) {
            Product product = requireProduct(storeId, item.productId());
            BigDecimal amount = item.unitCost().multiply(BigDecimal.valueOf(item.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            total = total.add(amount);
            PurchaseOrderItem row = new PurchaseOrderItem();
            row.setProductId(product.getId());
            row.setProductName(product.getName());
            row.setQuantity(item.quantity());
            row.setUnitCost(item.unitCost().setScale(2, RoundingMode.HALF_UP));
            row.setAmount(amount);
            row.setCreatedAt(LocalDateTime.now());
            row.setDeleted(0);
            items.add(row);
        }
        order.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        orderMapper.insert(order);

        for (PurchaseOrderItem item : items) {
            item.setOrderId(order.getId());
            itemMapper.insert(item);
        }
        auditService.record("PURCHASE_CREATE", "purchase_order", order.getOrderNo(),
                "新建采购单，明细 " + items.size() + " 行，金额 " + order.getTotalAmount());
        return detail(storeId, order.getId());
    }

    /** 确认入库：状态机流转 + 逐行增加库存（同一事务，任一行失败整单回滚）。 */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseDtos.View confirm(Long storeId, Long orderId) {
        PurchaseOrder order = requireOrder(storeId, orderId);
        if (!PurchaseOrder.STATUS_DRAFT.equals(order.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "只有草稿状态的采购单可以确认入库，当前状态：" + statusText(order.getStatus()));
        }
        LocalDateTime now = LocalDateTime.now();
        // 条件更新：并发下只有一个请求能把 DRAFT 改成 CONFIRMED，另一个拿不到行 → 直接拒绝，避免库存被加两次
        int rows = orderMapper.transition(storeId, orderId, PurchaseOrder.STATUS_DRAFT,
                PurchaseOrder.STATUS_CONFIRMED, now, now);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "采购单状态已变化（可能已被确认或取消），请刷新后重试");
        }

        List<PurchaseOrderItem> items = itemMapper.listByOrder(orderId);
        for (PurchaseOrderItem item : items) {
            inventoryService.increase(storeId, item.getProductId(), item.getQuantity(),
                    "PURCHASE", order.getOrderNo(), "采购入库：" + order.getOrderNo());
        }
        auditService.record("PURCHASE_CONFIRM", "purchase_order", order.getOrderNo(),
                "确认入库，明细 " + items.size() + " 行，金额 " + order.getTotalAmount());
        log.info("采购单入库完成 orderNo={} items={}", order.getOrderNo(), items.size());
        return detail(storeId, orderId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long storeId, Long orderId) {
        PurchaseOrder order = requireOrder(storeId, orderId);
        if (!PurchaseOrder.STATUS_DRAFT.equals(order.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "已入库的采购单不能取消（需要走退货流程冲销）");
        }
        int rows = orderMapper.transition(storeId, orderId, PurchaseOrder.STATUS_DRAFT,
                PurchaseOrder.STATUS_CANCELED, null, LocalDateTime.now());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "采购单状态已变化，请刷新后重试");
        }
        auditService.record("PURCHASE_CANCEL", "purchase_order", order.getOrderNo(), "取消采购单");
    }

    public PageResult<PurchaseDtos.View> page(Long storeId, String status, long page, long size) {
        Page<PurchaseOrder> result = orderMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)),
                new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getStoreId, storeId)
                        .eq(status != null && !status.isBlank(), PurchaseOrder::getStatus, status)
                        .orderByDesc(PurchaseOrder::getId));
        List<PurchaseDtos.View> views = new ArrayList<>();
        for (PurchaseOrder order : result.getRecords()) {
            views.add(toView(order, List.of()));
        }
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), views);
    }

    public PurchaseDtos.View detail(Long storeId, Long orderId) {
        PurchaseOrder order = requireOrder(storeId, orderId);
        List<PurchaseOrderItem> items = itemMapper.listByOrder(orderId);
        return toView(order, items);
    }

    private PurchaseOrder requireOrder(Long storeId, Long orderId) {
        PurchaseOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getStoreId().equals(storeId)) {
            throw BizException.notFound("采购单");
        }
        return order;
    }

    private Product requireProduct(Long storeId, Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !product.getStoreId().equals(storeId)) {
            throw BizException.notFound("商品");
        }
        return product;
    }

    private PurchaseDtos.View toView(PurchaseOrder order, List<PurchaseOrderItem> items) {
        List<PurchaseDtos.ItemView> itemViews = new ArrayList<>(items.size());
        for (PurchaseOrderItem item : items) {
            itemViews.add(new PurchaseDtos.ItemView(item.getId(), item.getProductId(), item.getProductName(),
                    item.getQuantity(), item.getUnitCost(), item.getAmount()));
        }
        return new PurchaseDtos.View(order.getId(), order.getOrderNo(), order.getSupplierName(),
                order.getItemCount(), order.getTotalAmount(), order.getStatus(), statusText(order.getStatus()),
                order.getRemark(), order.getConfirmedAt(), order.getCreatedAt(), itemViews);
    }

    private String statusText(String status) {
        return switch (status) {
            case PurchaseOrder.STATUS_DRAFT -> "草稿";
            case PurchaseOrder.STATUS_CONFIRMED -> "已入库";
            case PurchaseOrder.STATUS_CANCELED -> "已取消";
            default -> status;
        };
    }
}
