package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.purchase.dto.PurchaseDtos;
import com.retailsuite.purchase.entity.PurchaseOrder;
import com.retailsuite.purchase.service.PurchaseService;
import com.retailsuite.store.entity.Store;
import com.retailsuite.store.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 采购流程：录单不动库存 → 确认入库 → 重复确认被状态机挡住。 */
@SpringBootTest
@ActiveProfiles("test")
class PurchaseFlowTest {

    @Autowired
    private PurchaseService purchaseService;
    @Autowired
    private ProductService productService;
    @Autowired
    private InventoryService inventoryService;
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
    void 录单不动库存_确认入库才增加库存并写流水() {
        ProductDtos.View product = createProduct("采购测试商品A", 5);
        int stockBefore = product.stock();

        PurchaseDtos.View draft = purchaseService.create(storeId, new PurchaseDtos.CreateRequest(
                "示例供应商", "第一次进货",
                List.of(new PurchaseDtos.ItemRequest(product.id(), 20, new BigDecimal("1.20")))));

        assertEquals(PurchaseOrder.STATUS_DRAFT, draft.status());
        assertEquals("草稿", draft.statusText());
        assertEquals(0, new BigDecimal("24.00").compareTo(draft.totalAmount()));
        assertEquals(stockBefore, productMapper.selectById(product.id()).getStock(), "草稿不应改动库存");

        PurchaseDtos.View confirmed = purchaseService.confirm(storeId, draft.id());

        assertEquals(PurchaseOrder.STATUS_CONFIRMED, confirmed.status());
        assertEquals(stockBefore + 20, productMapper.selectById(product.id()).getStock(), "确认后库存必须增加");

        List<InventoryDtos.FlowView> flows = inventoryService.recentFlows(storeId, product.id(), 10);
        assertTrue(flows.stream().anyMatch(f -> "PURCHASE".equals(f.refType()) && f.quantity() == 20),
                "采购入库必须留下流水：" + flows);
    }

    @Test
    void 同一商品多行会被合并成一条明细() {
        ProductDtos.View product = createProduct("采购测试商品B", 0);

        PurchaseDtos.View draft = purchaseService.create(storeId, new PurchaseDtos.CreateRequest(
                "示例供应商", null, List.of(
                new PurchaseDtos.ItemRequest(product.id(), 10, new BigDecimal("1.00")),
                new PurchaseDtos.ItemRequest(product.id(), 5, new BigDecimal("1.50")))));

        assertEquals(1, draft.itemCount(), "同一商品应合并为一行");
        assertEquals(15, draft.items().get(0).quantity());
        assertEquals(0, new BigDecimal("22.50").compareTo(draft.totalAmount()), "合并后金额 = 15 × 1.50");
    }

    @Test
    void 重复确认会被状态机拒绝_库存不会加两次() {
        ProductDtos.View product = createProduct("采购测试商品C", 0);
        PurchaseDtos.View draft = purchaseService.create(storeId, new PurchaseDtos.CreateRequest(
                null, null, List.of(new PurchaseDtos.ItemRequest(product.id(), 30, new BigDecimal("2.00")))));

        purchaseService.confirm(storeId, draft.id());

        BizException error = assertThrows(BizException.class, () -> purchaseService.confirm(storeId, draft.id()));
        assertEquals(ErrorCode.CONFLICT, error.getCode());
        assertEquals(30, productMapper.selectById(product.id()).getStock(), "库存只能增加一次（30，不是 60）");
    }

    @Test
    void 已入库的采购单不能取消() {
        ProductDtos.View product = createProduct("采购测试商品D", 0);
        PurchaseDtos.View draft = purchaseService.create(storeId, new PurchaseDtos.CreateRequest(
                null, null, List.of(new PurchaseDtos.ItemRequest(product.id(), 5, new BigDecimal("1.00")))));
        purchaseService.confirm(storeId, draft.id());

        BizException error = assertThrows(BizException.class, () -> purchaseService.cancel(storeId, draft.id()));

        assertEquals(ErrorCode.CONFLICT, error.getCode());
        assertTrue(error.getMessage().contains("退货"), "提示应引导用户走退货流程：" + error.getMessage());
    }

    @Test
    void 采购单支持按状态分页查询() {
        ProductDtos.View product = createProduct("采购测试商品E", 0);
        PurchaseDtos.View draft = purchaseService.create(storeId, new PurchaseDtos.CreateRequest(
                null, null, List.of(new PurchaseDtos.ItemRequest(product.id(), 3, new BigDecimal("1.00")))));

        var drafts = purchaseService.page(storeId, PurchaseOrder.STATUS_DRAFT, 1, 50);
        var confirmed = purchaseService.page(storeId, PurchaseOrder.STATUS_CONFIRMED, 1, 50);

        assertTrue(drafts.records().stream().anyMatch(v -> v.orderNo().equals(draft.orderNo())));
        assertTrue(confirmed.records().stream().noneMatch(v -> v.orderNo().equals(draft.orderNo())));
    }

    private ProductDtos.View createProduct(String name, int initStock) {
        return productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                "BAR-" + System.nanoTime(), "规格", "件",
                new BigDecimal("1.00"), new BigDecimal("2.00"), initStock, 5));
    }
}
