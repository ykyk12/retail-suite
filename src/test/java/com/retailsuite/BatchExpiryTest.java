package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.service.ExpiryService;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.purchase.dto.PurchaseDtos;
import com.retailsuite.purchase.service.PurchaseService;
import com.retailsuite.sales.dto.SalesDtos;
import com.retailsuite.sales.entity.SaleOrder;
import com.retailsuite.sales.service.SaleService;
import com.retailsuite.store.entity.Store;
import com.retailsuite.store.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次与保质期（M1）：到期日推算、近效期先出（FEFO）、临期/过期预警、报损、批次与聚合库存一致性。
 *
 * 这些用例对应门店真实会追问的问题：
 *   "先卖哪一批？"（近效期先出，减少报损）
 *   "哪些快要过期了、压了多少钱？"（临期清单 + 成本金额）
 *   "过期了怎么处理？"（报损出库，扣对应批次）
 *   "批次数量对得上总库存吗？"（一致性核对）
 */
@SpringBootTest
@ActiveProfiles("test")
class BatchExpiryTest {

    @Autowired
    private ProductService productService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private ExpiryService expiryService;
    @Autowired
    private PurchaseService purchaseService;
    @Autowired
    private SaleService saleService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private StoreMapper storeMapper;

    private Long storeId;

    @BeforeEach
    void setUp() {
        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>().last("LIMIT 1"));
        assertNotNull(store);
        storeId = store.getId();
    }

    @Test
    void 入库按生产日期加保质期推算到期日() {
        ProductDtos.View product = createPerishable("批次测试牛奶", 90, "10.00", "5.00");

        PurchaseDtos.View order = purchaseService.create(storeId, new PurchaseDtos.CreateRequest(
                "批次供应商", "到货", List.of(new PurchaseDtos.ItemRequest(
                product.id(), 10, new BigDecimal("5.00"), LocalDate.now().minusDays(3), 90))));
        purchaseService.confirm(storeId, order.id());

        List<InventoryDtos.BatchView> batches = expiryService.batchesOf(storeId, product.id());
        assertEquals(1, batches.size());
        InventoryDtos.BatchView batch = batches.get(0);
        assertEquals(LocalDate.now().minusDays(3).plusDays(90), batch.expiryDate(), "到期日 = 生产日期 + 保质期");
        assertEquals(87, batch.daysToExpiry(), "已过 3 天，剩余 87 天");
        assertFalse(batch.expired());
        assertEquals(0, new BigDecimal("5.00").compareTo(batch.costPrice()), "批次成本价应取自采购单进价");
    }

    @Test
    void 未登记生产日期时按入库日推算并在备注留痕() {
        ProductDtos.View product = createPerishable("批次测试酸奶", 21, "6.00", "3.00");

        inventoryService.increase(storeId, product.id(), 5, "MANUAL", null, "临时入库", null);

        List<InventoryDtos.BatchView> batches = expiryService.batchesOf(storeId, product.id());
        assertEquals(1, batches.size());
        assertEquals(LocalDate.now().plusDays(21), batches.get(0).expiryDate());
        assertTrue(batches.get(0).remark().contains("未登记生产日期"),
                "必须留痕，避免把推算值当成真实保质期：" + batches.get(0).remark());
    }

    @Test
    void 出库按近效期先出_先扣最早到期批次() {
        ProductDtos.View product = createPerishable("批次测试面包", 30, "8.00", "4.00");

        // 先入一批 5 件（到期日更晚），再入一批 5 件（到期日更早）——顺序刻意颠倒
        inventoryService.increase(storeId, product.id(), 5, "PURCHASE", "PO-LATE", "后到期批次",
                new InventoryService.BatchInbound(LocalDate.now(), 30, new BigDecimal("4.00"), null, null));
        inventoryService.increase(storeId, product.id(), 5, "PURCHASE", "PO-SOON", "先到期批次",
                new InventoryService.BatchInbound(LocalDate.now().minusDays(25), 30, new BigDecimal("4.50"), null, null));

        // 卖出 6 件：应先扣 5 件早到期批次，再扣 1 件晚到期批次
        saleService.checkout(storeId, new SalesDtos.CheckoutRequest("REQ-BATCH-" + System.nanoTime(), "散客",
                List.of(new SalesDtos.ItemRequest(product.id(), 6, null)), BigDecimal.ZERO,
                SaleOrder.PAY_CASH, null));

        List<InventoryDtos.BatchView> batches = expiryService.batchesOf(storeId, product.id());
        assertEquals(2, batches.size());
        // 列表按到期日升序：第一条是"先到期批次"，应已耗尽，第二条剩 4 件
        assertEquals(0, batches.get(0).quantity(), "近效期批次应被优先扣完");
        assertEquals(4, batches.get(1).quantity());
        assertEquals(4, productMapper.selectById(product.id()).getStock());
    }

    @Test
    void 临期与过期清单按剩余天数给出且金额按成本价计算() {
        ProductDtos.View product = createPerishable("批次测试临期品", 40, "12.00", "6.00");

        inventoryService.increase(storeId, product.id(), 2, "PURCHASE", "PO-EXPIRING", "临期批次",
                new InventoryService.BatchInbound(LocalDate.now().minusDays(35), 40, new BigDecimal("6.00"), null, null));
        inventoryService.increase(storeId, product.id(), 3, "PURCHASE", "PO-EXPIRED", "已过期批次",
                new InventoryService.BatchInbound(LocalDate.now().minusDays(50), 40, new BigDecimal("6.00"), null, null));

        List<InventoryDtos.BatchView> expiring = expiryService.expiring(storeId, 30);
        List<InventoryDtos.BatchView> expired = expiryService.expired(storeId);
        InventoryDtos.ExpirySummary summary = expiryService.summary(storeId, 30);

        assertTrue(expiring.stream().anyMatch(b -> b.productId().equals(product.id()) && b.daysToExpiry() != null
                && b.daysToExpiry() <= 30), "5 天后到期的批次应出现在临期清单：" + expiring);
        assertTrue(expired.stream().anyMatch(b -> b.productId().equals(product.id())), "已过期批次应单独列出");
        assertEquals(30, summary.alertDays());
        assertTrue(summary.expiringAmount().compareTo(BigDecimal.ZERO) > 0, "临期金额按批次成本价计算");
        assertTrue(summary.expiredAmount().compareTo(new BigDecimal("18.00")) >= 0, "过期 3 件 × 6 元 = 18 元");
    }

    @Test
    void 报损扣减对应批次并写LOSS流水_必须填原因() {
        ProductDtos.View product = createPerishable("批次测试报损", 10, "5.00", "2.00");
        inventoryService.increase(storeId, product.id(), 4, "PURCHASE", "PO-LOSS", "待报损批次",
                new InventoryService.BatchInbound(LocalDate.now().minusDays(15), 10, new BigDecimal("2.00"), null, null));

        InventoryDtos.BatchView batch = expiryService.expired(storeId).stream()
                .filter(b -> b.productId().equals(product.id())).findFirst().orElseThrow();

        var flow = inventoryService.loss(storeId, new InventoryDtos.LossRequest(
                product.id(), 2, batch.batchNo(), "过期下架报损"));

        assertEquals("LOSS", flow.getType());
        assertEquals(-2, flow.getQuantity());
        assertEquals(batch.id(), flow.getBatchId(), "报损流水要能追到具体批次");
        assertEquals(2, productMapper.selectById(product.id()).getStock(), "报损后库存应减少 2");

        BizException noReason = assertThrows(BizException.class, () -> inventoryService.loss(storeId,
                new InventoryDtos.LossRequest(product.id(), 1, null, " ")));
        assertEquals(ErrorCode.BAD_REQUEST, noReason.getCode());
    }

    @Test
    void 批次数量与聚合库存始终一致() {
        ProductDtos.View product = createPerishable("批次测试一致性", 60, "9.00", "4.00");
        inventoryService.increase(storeId, product.id(), 7, "PURCHASE", "PO-A", "第一批",
                new InventoryService.BatchInbound(LocalDate.now(), 60, new BigDecimal("4.00"), null, null));
        inventoryService.increase(storeId, product.id(), 3, "PURCHASE", "PO-B", "第二批",
                new InventoryService.BatchInbound(LocalDate.now().minusDays(1), 60, new BigDecimal("4.20"), null, null));
        saleService.checkout(storeId, new SalesDtos.CheckoutRequest("REQ-CONSIST-" + System.nanoTime(), "散客",
                List.of(new SalesDtos.ItemRequest(product.id(), 4, null)), BigDecimal.ZERO,
                SaleOrder.PAY_CASH, null));
        inventoryService.adjust(storeId, new InventoryDtos.AdjustRequest(product.id(), -2, "盘点盘亏"));

        Map<Long, Object> mismatch = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : inventoryService.batchMismatches(storeId)) {
            mismatch.put((Long) row.get("productId"), row.get("diff"));
        }

        assertFalse(mismatch.containsKey(product.id()),
                "Σ批次 必须等于聚合库存，差异=" + mismatch.get(product.id()));
        assertEquals(4, productMapper.selectById(product.id()).getStock());
    }

    /** 创建一个带保质期的商品（保质期天数 > 0 才会追踪到期日）。 */
    private ProductDtos.View createPerishable(String name, Integer shelfLifeDays, String salePrice, String purchasePrice) {
        return productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                "BAR-" + System.nanoTime(), "规格", "件",
                new BigDecimal(purchasePrice), new BigDecimal(salePrice), 0, 3, shelfLifeDays));
    }
}
