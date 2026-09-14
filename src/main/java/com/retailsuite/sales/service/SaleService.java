package com.retailsuite.sales.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.retailsuite.audit.AuditService;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.common.Ids;
import com.retailsuite.common.PageResult;
import com.retailsuite.config.AppProperties;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.metrics.BusinessMetrics;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.sales.dto.SalesDtos;
import com.retailsuite.sales.entity.SaleOrder;
import com.retailsuite.sales.entity.SaleOrderItem;
import com.retailsuite.sales.mapper.SaleOrderItemMapper;
import com.retailsuite.sales.mapper.SaleOrderMapper;
import com.retailsuite.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 收银服务：下单（幂等 + 扣库存 + 落明细）与退货（部分退 + 回补库存）。
 *
 * 幂等为什么这么做（这是本项目最值得讲的一处）：
 * 1) 先用 requestId 查一次：命中就直接返回首次结果（快路径，不产生任何副作用）；
 * 2) 没命中才真正下单，而**最终兜底是 sale_order.request_id 上的唯一索引**——
 *    两个请求同时冲进来时，数据库只允许一个插入成功，另一个拿到 DuplicateKeyException；
 * 3) 捕获到冲突后回查首次结果返回：语义上"同一个请求只会产生一笔订单"，且不会重复扣库存。
 *
 * 为什么用 TransactionTemplate 而不是把整个方法标 @Transactional：
 * 如果把"查重 + 插入"放在同一个事务里，唯一键冲突会把事务标记为 rollback-only，
 * 之后再回查就会失败（UnexpectedRollbackException）。所以事务边界只包住"真正的下单动作"，
 * 冲突后的回查在事务之外执行——这个坑很多人踩过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaleService {

    private static final Set<String> PAY_METHODS = new LinkedHashSet<>(
            List.of(SaleOrder.PAY_CASH, SaleOrder.PAY_WECHAT, SaleOrder.PAY_ALIPAY, SaleOrder.PAY_CARD));

    private final SaleOrderMapper orderMapper;
    private final SaleOrderItemMapper itemMapper;
    private final ProductMapper productMapper;
    private final InventoryService inventoryService;
    private final AuditService auditService;
    private final AppProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final BusinessMetrics metrics;

    /** 收银结算（幂等）。 */
    public SalesDtos.View checkout(Long storeId, SalesDtos.CheckoutRequest request) {
        int maxItems = properties.getOrder().getMaxItemsPerOrder();
        if (request.items().size() > maxItems) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单笔订单最多 " + maxItems + " 行明细");
        }
        if (!PAY_METHODS.contains(request.payMethod())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的支付方式：" + request.payMethod()
                    + "（可选 " + PAY_METHODS + "）");
        }

        // 快路径：同一个 requestId 再来一次，直接返回首次结果（按本店过滤，杜绝跨门店泄露）
        SaleOrder existing = orderMapper.selectByRequestId(storeId, request.requestId());
        if (existing != null) {
            log.info("幂等命中 requestId={} orderNo={}", request.requestId(), existing.getOrderNo());
            return toView(existing, true, null);
        }

        SaleOrder created;
        try {
            created = transactionTemplate.execute(status -> doCheckout(storeId, request));
        } catch (DuplicateKeyException e) {
            // 并发重复提交：唯一索引兜底，回查本店首次结果（此时原事务已回滚，库存没有被扣两次）
            SaleOrder first = orderMapper.selectByRequestId(storeId, request.requestId());
            if (first == null) {
                // 命中的是**别的门店**的同 request_id（全局唯一索引），不是本店的幂等重试：
                // 不能把别家订单返回，也不能静默放过——明确报冲突，让前端换一个幂等键
                throw new BizException(ErrorCode.CONFLICT,
                        "请求流水号与其他门店的单据冲突，请更换 requestId 后重试");
            }
            log.warn("并发重复提交被唯一索引拦截 requestId={}，返回首次订单 {}",
                    request.requestId(), first.getOrderNo());
            auditService.record("SALE_DUPLICATE_BLOCKED", "sale_order", first.getOrderNo(),
                    "并发重复提交被幂等键拦截，requestId=" + request.requestId());
            return toView(first, true, null);
        }
        metrics.checkoutSuccess(storeId, request.payMethod());
        return toView(created, false, null);
    }

    /** 事务内的下单动作：落单 → 扣库存（条件更新，不足即抛异常回滚）→ 落明细 → 回写金额。 */
    private SaleOrder doCheckout(Long storeId, SalesDtos.CheckoutRequest request) {
        LocalDateTime now = LocalDateTime.now();

        // 同一商品多行合并（避免同商品两条明细把数量算重）——保留最后一次录入的单价
        Map<Long, SalesDtos.ItemRequest> merged = new LinkedHashMap<>();
        for (SalesDtos.ItemRequest item : request.items()) {
            SalesDtos.ItemRequest previous = merged.get(item.productId());
            if (previous == null) {
                merged.put(item.productId(), item);
            } else {
                merged.put(item.productId(), new SalesDtos.ItemRequest(item.productId(),
                        previous.quantity() + item.quantity(),
                        item.unitPrice() == null ? previous.unitPrice() : item.unitPrice()));
            }
        }

        SaleOrder order = new SaleOrder();
        order.setStoreId(storeId);
        order.setOrderNo(Ids.orderNo("SO"));
        order.setRequestId(request.requestId());
        order.setCustomerName(request.customerName());
        order.setItemCount(merged.size());
        order.setTotalAmount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPayAmount(BigDecimal.ZERO);
        order.setRefundAmount(BigDecimal.ZERO);
        order.setPayMethod(request.payMethod());
        order.setStatus(SaleOrder.STATUS_PAID);
        order.setRemark(request.remark());
        order.setOperatorId(UserContext.currentUserId());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setDeleted(0);
        // 唯一索引在这里生效：并发重复提交只会有一个事务插入成功
        orderMapper.insert(order);

        BigDecimal total = BigDecimal.ZERO;
        List<SaleOrderItem> items = new ArrayList<>(merged.size());
        for (SalesDtos.ItemRequest item : merged.values()) {
            Product product = requireProduct(storeId, item.productId());
            if (product.getStatus() != null && product.getStatus() == 0) {
                throw new BizException(ErrorCode.CONFLICT, "商品「" + product.getName() + "」已停售，无法结算");
            }
            BigDecimal unitPrice = item.unitPrice() == null
                    ? product.getSalePrice().setScale(2, RoundingMode.HALF_UP)
                    : item.unitPrice().setScale(2, RoundingMode.HALF_UP);
            BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(item.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            // 扣库存：库存在这里被真正扣减，不足会抛 STOCK_NOT_ENOUGH，整笔订单回滚
            inventoryService.decrease(storeId, product.getId(), item.quantity(), "SALE",
                    order.getOrderNo(), "收银出库：" + order.getOrderNo());
            total = total.add(amount);

            SaleOrderItem row = new SaleOrderItem();
            row.setOrderId(order.getId());
            row.setProductId(product.getId());
            row.setProductName(product.getName());
            row.setBarcode(product.getBarcode());
            row.setQuantity(item.quantity());
            row.setRefundedQuantity(0);
            row.setUnitPrice(unitPrice);
            // 冻结成本价：财务报表（毛利）以后不因商品改价而变
            row.setCostPrice(product.getPurchasePrice() == null ? BigDecimal.ZERO
                    : product.getPurchasePrice().setScale(2, RoundingMode.HALF_UP));
            row.setAmount(amount);
            row.setCreatedAt(now);
            row.setDeleted(0);
            items.add(row);
        }
        for (SaleOrderItem row : items) {
            itemMapper.insert(row);
        }

        BigDecimal discount = request.discountAmount() == null ? BigDecimal.ZERO
                : request.discountAmount().setScale(2, RoundingMode.HALF_UP);
        if (discount.compareTo(total) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "折扣 " + discount + " 不能大于订单金额 " + total);
        }
        BigDecimal payAmount = total.subtract(discount).setScale(2, RoundingMode.HALF_UP);

        SaleOrder update = new SaleOrder();
        update.setId(order.getId());
        update.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        update.setDiscountAmount(discount);
        update.setPayAmount(payAmount);
        update.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(update);

        order.setTotalAmount(update.getTotalAmount());
        order.setDiscountAmount(discount);
        order.setPayAmount(payAmount);

        auditService.record("SALE_CHECKOUT", "sale_order", order.getOrderNo(),
                "收银结算 " + items.size() + " 行，应收 " + total + "，实收 " + payAmount);
        log.info("收银完成 orderNo={} items={} total={} pay={}", order.getOrderNo(), items.size(), total, payAmount);
        return order;
    }

    /** 退货（支持部分退）：明细用量条件更新防超退，库存回补并写流水。 */
    @Transactional(rollbackFor = Exception.class)
    public SalesDtos.View refund(Long storeId, Long orderId, SalesDtos.RefundRequest request) {
        SaleOrder order = requireOrder(storeId, orderId);
        if (!SaleOrder.STATUS_PAID.equals(order.getStatus())
                && !SaleOrder.STATUS_PARTIAL_REFUNDED.equals(order.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "当前订单状态不支持退货：" + statusText(order.getStatus()));
        }

        // 退款金额一致性：折扣是"整单级"的，退某一行时要按它占应收金额的比例分摊折扣，
        // 而不是按行挂牌价全额退。否则"顾客只付了 150，却退给他 200"，钱箱直接倒亏。
        BigDecimal totalAmount = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
        BigDecimal discountAmount = order.getDiscountAmount() == null ? BigDecimal.ZERO : order.getDiscountAmount();
        BigDecimal payAmount = order.getPayAmount() == null ? totalAmount : order.getPayAmount();
        // 实收系数 = 实收 / 应收（无折扣时为 1）；应收为 0 时退 0
        BigDecimal paidRatio = totalAmount.compareTo(BigDecimal.ZERO) > 0
                ? payAmount.divide(totalAmount, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        // 本次退款前已退金额（来自 DB 快照，applyRefund 会在其上累加）
        BigDecimal alreadyRefunded = order.getRefundAmount() == null ? BigDecimal.ZERO : order.getRefundAmount();
        BigDecimal remainRefundable = payAmount.subtract(alreadyRefunded);

        BigDecimal refundDelta = BigDecimal.ZERO;
        for (SalesDtos.RefundItemRequest refundItem : request.items()) {
            SaleOrderItem item = requireItem(orderId, refundItem.orderItemId());
            // 条件更新：quantity - refunded_quantity >= 本次退货数量，否则影响行数为 0（并发下也不会退超）
            int rows = itemMapper.addRefundedQuantity(orderId, item.getId(), refundItem.quantity());
            if (rows == 0) {
                throw new BizException(ErrorCode.CONFLICT, "退货数量超过可退数量：商品「" + item.getProductName()
                        + "」已退 " + item.getRefundedQuantity() + " / " + item.getQuantity());
            }
            inventoryService.increase(storeId, item.getProductId(), refundItem.quantity(), "REFUND",
                    order.getOrderNo(), "退货入库：" + order.getOrderNo());
            // 行退款额 = 行挂牌金额 × 实收系数（折扣按比例分摊到本行）
            BigDecimal lineRefund = item.getUnitPrice()
                    .multiply(BigDecimal.valueOf(refundItem.quantity()))
                    .multiply(paidRatio)
                    .setScale(2, RoundingMode.HALF_UP);
            refundDelta = refundDelta.add(lineRefund);
        }

        // 兜底封顶：累计退款绝不超过实收（多次部分退款的舍入误差在这里被吸收，整单全退恰好等于实收）
        if (refundDelta.compareTo(remainRefundable) > 0) {
            log.warn("退款金额按实收封顶 orderNo={} 计算={} 可退={}", order.getOrderNo(), refundDelta, remainRefundable);
            refundDelta = remainRefundable;
        }
        if (refundDelta.compareTo(BigDecimal.ZERO) < 0) {
            refundDelta = BigDecimal.ZERO;
        }

        List<SaleOrderItem> items = itemMapper.listByOrder(orderId);
        boolean fullyRefunded = items.stream()
                .allMatch(item -> item.getRefundedQuantity() != null && item.getQuantity() != null
                        && item.getRefundedQuantity() >= item.getQuantity());
        String status = fullyRefunded ? SaleOrder.STATUS_REFUNDED : SaleOrder.STATUS_PARTIAL_REFUNDED;
        orderMapper.applyRefund(storeId, orderId, refundDelta.setScale(2, RoundingMode.HALF_UP), status,
                LocalDateTime.now());
        auditService.record("SALE_REFUND", "sale_order", order.getOrderNo(),
                "退货 " + request.items().size() + " 行，退款 " + refundDelta + "，状态 → " + statusText(status));
        metrics.refundSuccess(storeId);
        log.info("退货完成 orderNo={} 退款={} status={}", order.getOrderNo(), refundDelta, status);
        return detail(storeId, orderId);
    }

    public PageResult<SalesDtos.View> page(Long storeId, String status, String keyword, long page, long size) {
        Page<SaleOrder> result = orderMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)),
                new LambdaQueryWrapper<SaleOrder>()
                        .eq(SaleOrder::getStoreId, storeId)
                        .eq(status != null && !status.isBlank(), SaleOrder::getStatus, status)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(SaleOrder::getOrderNo, keyword)
                                .or().like(SaleOrder::getCustomerName, keyword))
                        .orderByDesc(SaleOrder::getId));
        List<SalesDtos.View> views = new ArrayList<>();
        for (SaleOrder order : result.getRecords()) {
            views.add(toView(order, false, null));
        }
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), views);
    }

    public SalesDtos.View detail(Long storeId, Long orderId) {
        SaleOrder order = requireOrder(storeId, orderId);
        return toView(order, false, null);
    }

    public SaleOrder requireOrder(Long storeId, Long orderId) {
        SaleOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getStoreId().equals(storeId)) {
            throw BizException.notFound("订单");
        }
        return order;
    }

    private SaleOrderItem requireItem(Long orderId, Long itemId) {
        SaleOrderItem item = itemMapper.selectById(itemId);
        if (item == null || !item.getOrderId().equals(orderId)) {
            throw BizException.notFound("订单明细");
        }
        return item;
    }

    private Product requireProduct(Long storeId, Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !product.getStoreId().equals(storeId)) {
            throw BizException.notFound("商品");
        }
        return product;
    }

    private SalesDtos.View toView(SaleOrder order, boolean duplicated, List<SaleOrderItem> provided) {
        List<SaleOrderItem> items = provided == null ? itemMapper.listByOrder(order.getId()) : provided;
        List<SalesDtos.ItemView> itemViews = new ArrayList<>(items.size());
        for (SaleOrderItem item : items) {
            itemViews.add(new SalesDtos.ItemView(item.getId(), item.getProductId(), item.getProductName(),
                    item.getBarcode(), item.getQuantity(), item.getRefundedQuantity(),
                    item.getUnitPrice(), item.getAmount()));
        }
        return new SalesDtos.View(order.getId(), order.getOrderNo(), order.getRequestId(), order.getCustomerName(),
                order.getItemCount(), order.getTotalAmount(), order.getDiscountAmount(), order.getPayAmount(),
                order.getRefundAmount(), order.getPayMethod(), order.getStatus(), statusText(order.getStatus()),
                order.getRemark(), order.getCreatedAt(), duplicated, itemViews);
    }

    private String statusText(String status) {
        return switch (status) {
            case SaleOrder.STATUS_PAID -> "已收款";
            case SaleOrder.STATUS_PARTIAL_REFUNDED -> "部分退货";
            case SaleOrder.STATUS_REFUNDED -> "整单退货";
            default -> status;
        };
    }
}
