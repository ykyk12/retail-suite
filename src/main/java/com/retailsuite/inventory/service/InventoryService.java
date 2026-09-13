package com.retailsuite.inventory.service;

import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.common.Ids;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.entity.InventoryFlow;
import com.retailsuite.inventory.entity.ProductBatch;
import com.retailsuite.inventory.mapper.InventoryFlowMapper;
import com.retailsuite.inventory.mapper.ProductBatchMapper;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存服务：**唯一**允许修改商品库存的入口，并且是批次与保质期管理的唯一入口。
 *
 * ── 为什么聚合 + 批次两层 ──────────────────────────────────────────────
 * product.stock 是聚合库存，收银扣减走一条条件更新（`where stock >= n`），性能最好；
 * product_batch 记录每一批货的剩余量与到期日，用于临期预警、报损与批次成本。
 * 两层在**同一个本地事务**内同步，并由对账接口校验 Σ批次 = 聚合库存。
 *
 * ── 出库为什么是"近效期先出" ──────────────────────────────────────────
 * 食品/生鲜按到期日升序扣减（expiry_date ASC，无到期日的排最后）能最大限度减少报损，
 * 这就是零售里的 FIFO 变体（FEFO, First-Expired-First-Out）。纯按下单时间 FIFO 会让近效期货烂在库里。
 *
 * ── 并发安全 ─────────────────────────────────────────────────────────
 * 先做聚合的条件更新（`update product ... where stock >= n`），这一步会持有该商品行的排他锁，
 * 因此**同一商品的批次扣减天然被串行化**，不需要额外加锁或分布式锁。
 * 批次的 `consume` 也是条件更新（`where quantity >= n`），双保险。
 *
 * ── 已知局限（写在文档里，不装作完美）────────────────────────────────
 * 一次出库可能跨多个批次，而库存流水只有一行：当前记首个批次 id，完整批次消耗需在批次页追溯。
 * 若将来要做"按批次成本精确核算毛利到行"，应把流水拆成一行一批次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    public static final String TYPE_IN = "IN";
    public static final String TYPE_OUT = "OUT";
    public static final String TYPE_ADJUST = "ADJUST";
    public static final String TYPE_LOSS = "LOSS";

    /** 入库批次信息（采购入库时由采购单明细带过来）。 */
    public record BatchInbound(LocalDate productionDate, Integer shelfLifeDays, BigDecimal costPrice,
                               Long purchaseOrderId, String remark) {
    }

    private final ProductMapper productMapper;
    private final InventoryFlowMapper flowMapper;
    private final ProductBatchMapper batchMapper;

    // ------------------------------------------------------------------ 出库

    /** 扣减库存（销售出库）。 */
    @Transactional(rollbackFor = Exception.class)
    public InventoryFlow decrease(Long storeId, Long productId, int quantity, String refType, String refNo, String remark) {
        return issue(storeId, productId, quantity, TYPE_OUT, refType, refNo, remark);
    }

    /** 报损出库：过期/破损商品下架，同样按近效期先出扣批次，并记 LOSS 流水。 */
    @Transactional(rollbackFor = Exception.class)
    public InventoryFlow loss(Long storeId, InventoryDtos.LossRequest request) {
        if (request.remark() == null || request.remark().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "报损必须填写原因（便于盘点与追责）");
        }
        return issue(storeId, request.productId(), request.quantity(), TYPE_LOSS,
                "LOSS", request.batchNo(), request.remark());
    }

    private InventoryFlow issue(Long storeId, Long productId, int quantity, String flowType,
                                String refType, String refNo, String remark) {
        if (quantity <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "出库数量必须大于 0");
        }
        Product product = requireProduct(storeId, productId);
        int rows = productMapper.decreaseStock(storeId, productId, quantity, LocalDateTime.now());
        if (rows == 0) {
            Product latest = productMapper.selectById(productId);
            int stock = safeStock(latest);
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                    "商品「" + product.getName() + "」库存不足：需要 " + quantity + "，当前 " + stock);
        }
        Long batchId = issueBatches(storeId, product, quantity);
        return recordFlow(storeId, productId, flowType, -quantity, refType, refNo, remark, batchId);
    }

    /**
     * 按近效期优先扣批次；返回首个被扣减的批次 id（用于流水追溯）。
     * 若批次数量不足以覆盖（说明批次与聚合库存不一致），直接抛错回滚——宁可失败也不静默把账弄乱。
     */
    private Long issueBatches(Long storeId, Product product, int quantity) {
        List<ProductBatch> batches = batchMapper.selectForIssue(storeId, product.getId());
        int remaining = quantity;
        Long firstBatchId = null;
        for (ProductBatch batch : batches) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, batch.getQuantity() == null ? 0 : batch.getQuantity());
            if (take <= 0) {
                continue;
            }
            int consumed = batchMapper.consume(batch.getId(), take, LocalDateTime.now());
            if (consumed > 0) {
                remaining -= take;
                if (firstBatchId == null) {
                    firstBatchId = batch.getId();
                }
            }
        }
        if (remaining > 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "批次库存不足：商品「" + product.getName() + "」还需扣 " + remaining
                            + " 件但可用批次已用尽，请先运行批次对账（Σ批次 应等于聚合库存）");
        }
        return firstBatchId;
    }

    // ------------------------------------------------------------------ 入库

    /** 增加库存（无批次信息：盘点盘盈等）。 */
    @Transactional(rollbackFor = Exception.class)
    public InventoryFlow increase(Long storeId, Long productId, int quantity, String refType, String refNo, String remark) {
        return increase(storeId, productId, quantity, refType, refNo, remark, null);
    }

    /** 增加库存并登记批次（采购入库走这条）。 */
    @Transactional(rollbackFor = Exception.class)
    public InventoryFlow increase(Long storeId, Long productId, int quantity, String refType, String refNo,
                                  String remark, BatchInbound inbound) {
        if (quantity <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "入库数量必须大于 0");
        }
        Product product = requireProduct(storeId, productId);
        int rows = productMapper.increaseStock(storeId, productId, quantity, LocalDateTime.now());
        if (rows == 0) {
            throw BizException.notFound("商品");
        }
        Long batchId = receiveBatch(storeId, product, quantity, inbound, remark);
        return recordFlow(storeId, productId, TYPE_IN, quantity, refType, refNo, remark, batchId);
    }

    /** 建立批次并返回批次 id。到期日按"生产日期 + 保质期"推算；缺生产日期时用入库日近似并留痕。 */
    private Long receiveBatch(Long storeId, Product product, int quantity, BatchInbound inbound, String remark) {
        Integer shelfLife = inbound != null && inbound.shelfLifeDays() != null
                ? inbound.shelfLifeDays() : product.getShelfLifeDays();
        LocalDate production = inbound == null ? null : inbound.productionDate();
        LocalDate expiry = null;
        String batchRemark = inbound == null ? null : inbound.remark();
        if (shelfLife != null && shelfLife > 0) {
            if (production != null) {
                expiry = production.plusDays(shelfLife);
            } else {
                // 没登记生产日期时按入库日推算：保守估计（真实货龄可能更老），并在备注里留痕便于人工核对
                expiry = LocalDate.now().plusDays(shelfLife);
                batchRemark = appendRemark(batchRemark, "未登记生产日期，按入库日+" + shelfLife + "天推算到期日");
            }
        }
        ProductBatch batch = new ProductBatch();
        batch.setStoreId(storeId);
        batch.setProductId(product.getId());
        batch.setBatchNo(Ids.batchNo());
        batch.setProductionDate(production);
        batch.setExpiryDate(expiry);
        batch.setQuantity(quantity);
        batch.setCostPrice(inbound != null && inbound.costPrice() != null
                ? inbound.costPrice() : product.getPurchasePrice());
        batch.setPurchaseOrderId(inbound == null ? null : inbound.purchaseOrderId());
        batch.setRemark(appendRemark(batchRemark, remark));
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        batch.setDeleted(0);
        batchMapper.insert(batch);
        log.info("入库批次建立 storeId={} productId={} batchNo={} 数量={} 到期日={}",
                storeId, product.getId(), batch.getBatchNo(), quantity, expiry);
        return batch.getId();
    }

    // ------------------------------------------------------------------ 盘点调整

    /**
     * 盘点调整。
     * 注意事务写在**这个方法**上：内部调用的 adjustInternal 是自调用，
     * Spring 基于代理的事务不会对自调用生效（经典坑），所以入口方法必须自己带 @Transactional。
     */
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
        return adjustInternal(storeId, product, (int) target, request.remark());
    }

    /** 盘点的实际动作（由 adjust 的事务包裹，因此这里不再单独标 @Transactional）。 */
    private InventoryFlow adjustInternal(Long storeId, Product product, int targetStock, String remark) {
        int current = safeStock(product);
        int delta = targetStock - current;
        int rows = productMapper.resetStock(storeId, product.getId(), targetStock, LocalDateTime.now());
        if (rows == 0) {
            throw BizException.notFound("商品");
        }
        Long batchId;
        if (delta < 0) {
            batchId = issueBatches(storeId, product, -delta);
        } else {
            batchId = receiveBatch(storeId, product, delta, null, "盘点盘盈");
        }
        return recordFlow(storeId, product.getId(), TYPE_ADJUST, delta, "MANUAL", null, remark, batchId);
    }

    // ------------------------------------------------------------------ 查询

    public List<InventoryDtos.FlowView> recentFlows(Long storeId, Long productId, int limit) {
        List<InventoryFlow> flows = flowMapper.recentByProduct(storeId, productId, Math.min(Math.max(limit, 1), 200));
        List<InventoryDtos.FlowView> views = new ArrayList<>(flows.size());
        for (InventoryFlow flow : flows) {
            views.add(toView(storeId, flow));
        }
        return views;
    }

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

    /** 某商品的批次列表（含已售罄与已过期，用于追溯）。 */
    public List<InventoryDtos.BatchView> batches(Long storeId, Long productId) {
        Product product = requireProduct(storeId, productId);
        List<ProductBatch> batches = batchMapper.listByProduct(storeId, productId);
        LocalDate today = LocalDate.now();
        List<InventoryDtos.BatchView> views = new ArrayList<>(batches.size());
        for (ProductBatch batch : batches) {
            Long days = batch.daysToExpiry(today);
            views.add(new InventoryDtos.BatchView(batch.getId(), batch.getBatchNo(), batch.getProductId(),
                    product.getName(), batch.getProductionDate(), batch.getExpiryDate(), batch.getQuantity(),
                    batch.getCostPrice(), batch.getRemark(), days, batch.expiredAt(today)));
        }
        return views;
    }

    /** 批次与聚合库存一致性核对：返回不一致的商品（正常应为空）。 */
    public List<Map<String, Object>> batchMismatches(Long storeId) {
        Map<Long, Integer> batchTotals = new LinkedHashMap<>();
        for (Map<String, Object> row : batchMapper.sumQuantityGroupByProduct(storeId)) {
            Object id = row.get("product_id") != null ? row.get("product_id") : row.get("PRODUCT_ID");
            Object qty = row.get("quantity") != null ? row.get("quantity") : row.get("QUANTITY");
            if (id != null) {
                batchTotals.put(Long.valueOf(String.valueOf(id)),
                        qty == null ? 0 : Integer.parseInt(String.valueOf(qty)));
            }
        }
        List<Map<String, Object>> mismatches = new ArrayList<>();
        for (Product product : productMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                        .eq(Product::getStoreId, storeId))) {
            int aggregate = safeStock(product);
            int batchTotal = batchTotals.getOrDefault(product.getId(), 0);
            if (aggregate != batchTotal) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("productId", product.getId());
                row.put("productName", product.getName());
                row.put("stock", aggregate);
                row.put("batchTotal", batchTotal);
                row.put("diff", aggregate - batchTotal);
                mismatches.add(row);
            }
        }
        return mismatches;
    }

    // ------------------------------------------------------------------ 内部

    public Product requireProduct(Long storeId, Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !product.getStoreId().equals(storeId)) {
            throw BizException.notFound("商品");
        }
        return product;
    }

    private InventoryFlow recordFlow(Long storeId, Long productId, String type, int delta,
                                     String refType, String refNo, String remark, Long batchId) {
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
        flow.setBatchId(batchId);
        flow.setOperatorId(UserContext.currentUserId());
        flow.setCreatedAt(LocalDateTime.now());
        flow.setDeleted(0);
        flowMapper.insert(flow);
        log.info("库存变动 storeId={} productId={} type={} delta={} {}→{} batchId={} ref={}",
                storeId, productId, type, delta, flow.getBeforeStock(), afterStock, batchId, refNo);
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
                flow.getRefType(), flow.getRefNo(), flow.getRemark(), flow.getBatchId(), flow.getCreatedAt());
    }

    private String appendRemark(String base, String extra) {
        if (extra == null || extra.isBlank()) {
            return base;
        }
        if (base == null || base.isBlank()) {
            return extra;
        }
        return base + "；" + extra;
    }
}
