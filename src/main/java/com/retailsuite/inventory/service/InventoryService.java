package com.retailsuite.inventory.service;

import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.entity.InventoryFlow;
import com.retailsuite.inventory.mapper.InventoryFlowMapper;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 库存服务：**唯一**允许修改商品库存的入口。
 *
 * 三种扣减方式的取舍（这也是面试高频追问）：
 * 1) 条件更新（本项目选用）：`update product set stock = stock - n where id = ? and stock >= n`，
 *    一条语句内完成"判断 + 扣减"，靠行锁保证原子性；返回影响行数 0 即库存不足。
 *    优点：无锁等待开销、不会超卖、不需要分布式锁。
 * 2) 乐观锁（@Version，本项目在"读-改-写"式更新会用到）：冲突时抛异常让上层重试；
 *    适合"写少、算出新值依赖旧值"的场景，但高并发下重试成本高。
 * 3) 悲观锁（select ... for update）：串行化最直观，但会把同一商品的并发全部排队，
 *    收银高峰时容易把连接池占满——只有在"必须跨多张表持锁"时才值得。
 *
 * 事务边界：扣减库存 + 写流水 + 落单必须在一个本地事务里，任一失败全部回滚，
 * 这样绝不会出现"扣了库存却没有订单"或"有订单但没扣库存"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    public static final String TYPE_IN = "IN";
    public static final String TYPE_OUT = "OUT";
    public static final String TYPE_ADJUST = "ADJUST";

    private final ProductMapper productMapper;
    private final InventoryFlowMapper flowMapper;

    /**
     * 扣减库存（销售出库）。
     *
     * @return 写入的库存流水
     */
    @Transactional(rollbackFor = Exception.class)
    public InventoryFlow decrease(Long storeId, Long productId, int quantity, String refType, String refNo, String remark) {
        if (quantity <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "扣减数量必须大于 0");
        }
        Product product = requireProduct(storeId, productId);
        int rows = productMapper.decreaseStock(storeId, productId, quantity, LocalDateTime.now());
        if (rows == 0) {
            // 读回真实库存用于给收银员可读的提示（这里读到的是当前事务看到的值，够用）
            Product latest = productMapper.selectById(productId);
            int stock = latest == null || latest.getStock() == null ? 0 : latest.getStock();
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                    "商品「" + product.getName() + "」库存不足：需要 " + quantity + "，当前 " + stock);
        }
        return recordFlow(storeId, productId, TYPE_OUT, -quantity, refType, refNo, remark);
    }

    /** 增加库存（采购入库、退货回补）。 */
    @Transactional(rollbackFor = Exception.class)
    public InventoryFlow increase(Long storeId, Long productId, int quantity, String refType, String refNo, String remark) {
        if (quantity <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "入库数量必须大于 0");
        }
        requireProduct(storeId, productId);
        int rows = productMapper.increaseStock(storeId, productId, quantity, LocalDateTime.now());
        if (rows == 0) {
            throw BizException.notFound("商品");
        }
        return recordFlow(storeId, productId, TYPE_IN, quantity, refType, refNo, remark);
    }

    /** 盘点调整（delta 可正可负）。 */
    @Transactional(rollbackFor = Exception.class)
    public InventoryFlow adjust(Long storeId, InventoryDtos.AdjustRequest request) {
        if (request.delta() == null || request.delta() == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "调整数量不能为 0");
        }
        if (request.remark() == null || request.remark().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "库存调整必须填写原因（便于日后对账追责）");
        }
        Product product = requireProduct(storeId, request.productId());
        long target = (long) safeStock(product) + request.delta();
        if (target < 0) {
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                    "调整后库存不能为负：当前 " + safeStock(product) + "，调整 " + request.delta());
        }
        int rows = productMapper.resetStock(storeId, request.productId(), (int) target, LocalDateTime.now());
        if (rows == 0) {
            throw BizException.notFound("商品");
        }
        return recordFlow(storeId, request.productId(), TYPE_ADJUST, request.delta(),
                "MANUAL", null, request.remark());
    }

    /** 库存流水（按商品最近 N 条）。 */
    public List<InventoryDtos.FlowView> recentFlows(Long storeId, Long productId, int limit) {
        List<InventoryFlow> flows = flowMapper.recentByProduct(storeId, productId, Math.min(Math.max(limit, 1), 200));
        List<InventoryDtos.FlowView> views = new ArrayList<>(flows.size());
        for (InventoryFlow flow : flows) {
            views.add(toView(storeId, flow));
        }
        return views;
    }

    /** 库存预警：库存低于（含等于）各自阈值的商品。 */
    public List<InventoryDtos.LowStockItem> lowStockItems(Long storeId) {
        List<Product> products = productMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                .eq(Product::getStoreId, storeId)
                .eq(Product::getStatus, 1)
                .apply("stock <= low_stock_threshold")
                .orderByAsc(Product::getStock));
        List<InventoryDtos.LowStockItem> items = new ArrayList<>(products.size());
        for (Product product : products) {
            items.add(new InventoryDtos.LowStockItem(product.getId(), product.getName(), product.getBarcode(),
                    product.getStock(), product.getLowStockThreshold(), product.getUnit()));
        }
        return items;
    }

    private Product requireProduct(Long storeId, Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !product.getStoreId().equals(storeId)) {
            // 门店隔离：别的门店的商品对外表现成"不存在"，而不是 403（不泄漏资源是否存在）
            throw BizException.notFound("商品");
        }
        return product;
    }

    /**
     * 写流水。before 由"更新后的库存 - 变动量"反推，而不是先查再改：
     * 先查再改在并发下会记错（别人可能已经改过了）。这里因为整段在一个事务里，反推是准确的。
     */
    private InventoryFlow recordFlow(Long storeId, Long productId, String type, int delta,
                                     String refType, String refNo, String remark) {
        Product after = productMapper.selectById(productId);
        int afterStock = safeStock(after);
        InventoryFlow flow = new InventoryFlow();
        flow.setStoreId(storeId);
        flow.setProductId(productId);
        flow.setType(type);
        flow.setQuantity(delta);
        flow.setBeforeStock(afterStock - delta);
        flow.setAfterStock(afterStock);
        flow.setRefType(refType);
        flow.setRefNo(refNo);
        flow.setRemark(remark);
        flow.setOperatorId(UserContext.currentUserId());
        flow.setCreatedAt(LocalDateTime.now());
        flow.setDeleted(0);
        flowMapper.insert(flow);
        log.info("库存变动 storeId={} productId={} type={} delta={} {}→{} ref={}",
                storeId, productId, type, delta, flow.getBeforeStock(), afterStock, refNo);
        return flow;
    }

    private int safeStock(Product product) {
        return product == null || product.getStock() == null ? 0 : product.getStock();
    }

    private InventoryDtos.FlowView toView(Long storeId, InventoryFlow flow) {
        Product product = productMapper.selectById(flow.getProductId());
        return new InventoryDtos.FlowView(flow.getId(), flow.getProductId(),
                product == null ? "(已删除商品)" : product.getName(),
                flow.getType(), flow.getQuantity(), flow.getBeforeStock(), flow.getAfterStock(),
                flow.getRefType(), flow.getRefNo(), flow.getRemark(), flow.getCreatedAt());
    }
}
