package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.entity.StockAlert;
import com.retailsuite.inventory.mapper.StockAlertMapper;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.inventory.service.StockAlertService;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.service.ProductService;
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

/**
 * 低库存预警工单闭环：出库砸到阈值以下自动开 OPEN 工单，同商品不重复开，处理后可闭环。
 */
@SpringBootTest
@ActiveProfiles("test")
class StockAlertTest {

    @Autowired
    private ProductService productService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private StockAlertService stockAlertService;
    @Autowired
    private StockAlertMapper alertMapper;
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
    void 出库砸到阈值以下_自动开一条OPEN工单() {
        // 阈值 3，期初 5：先卖 3，库存变 2 <= 3，应开单
        ProductDtos.View product = createProduct("预警开单-" + nano(), 5, 3);
        inventoryService.decrease(storeId, product.id(), 3, "SALE", "TEST", "触发预警");

        List<InventoryDtos.AlertView> open = stockAlertService.listOpen(storeId);
        boolean found = open.stream().anyMatch(a -> a.productId().equals(product.id()));
        assertTrue(found, "库存跌破阈值后必须开出 OPEN 预警工单");
        InventoryDtos.AlertView alert = open.stream()
                .filter(a -> a.productId().equals(product.id())).findFirst().orElseThrow();
        assertEquals(2, alert.stockAtAlert());
        assertEquals(3, alert.threshold());
        assertEquals(StockAlert.STATUS_OPEN, alert.status());
    }

    @Test
    void 已存在OPEN工单时继续出库_不重复开单() {
        ProductDtos.View product = createProduct("预警去重-" + nano(), 6, 3);
        inventoryService.decrease(storeId, product.id(), 4, "SALE", "TEST", "第一次触发"); // 6->2
        inventoryService.decrease(storeId, product.id(), 1, "SALE", "TEST", "又卖一件");  // 2->1

        long openCount = alertMapper.selectCount(new LambdaQueryWrapper<StockAlert>()
                .eq(StockAlert::getStoreId, storeId)
                .eq(StockAlert::getProductId, product.id())
                .eq(StockAlert::getStatus, StockAlert.STATUS_OPEN));
        assertEquals(1, openCount, "同一商品已有 OPEN 工单时不得重复开单");
    }

    @Test
    void 闭环工单后_再次出库_重新开单() {
        ProductDtos.View product = createProduct("预警闭环-" + nano(), 5, 3);
        inventoryService.decrease(storeId, product.id(), 3, "SALE", "TEST", "触发"); // 5->2
        Long alertId = stockAlertService.listOpen(storeId).stream()
                .filter(a -> a.productId().equals(product.id())).findFirst().orElseThrow().id();

        stockAlertService.resolve(storeId, alertId, "已补货到货");
        assertEquals(0, stockAlertService.listOpen(storeId).stream()
                .filter(a -> a.productId().equals(product.id())).count(), "闭环后待处理列表不应再含该商品");

        // 再卖一件，库存 1 <= 3：应重新开一条新工单
        inventoryService.decrease(storeId, product.id(), 1, "SALE", "TEST", "闭环后又缺货");
        long openCount = alertMapper.selectCount(new LambdaQueryWrapper<StockAlert>()
                .eq(StockAlert::getStoreId, storeId)
                .eq(StockAlert::getProductId, product.id())
                .eq(StockAlert::getStatus, StockAlert.STATUS_OPEN));
        assertEquals(1, openCount, "闭环后再次跌破阈值应重新开单");
    }

    @Test
    void 重复闭环_报冲突() {
        ProductDtos.View product = createProduct("重复闭环-" + nano(), 5, 3);
        inventoryService.decrease(storeId, product.id(), 3, "SALE", "TEST", "触发");
        Long alertId = stockAlertService.listOpen(storeId).stream()
                .filter(a -> a.productId().equals(product.id())).findFirst().orElseThrow().id();
        stockAlertService.resolve(storeId, alertId, "ok");

        BizException e = assertThrows(BizException.class,
                () -> stockAlertService.resolve(storeId, alertId, "再点一次"));
        assertEquals(ErrorCode.CONFLICT, e.getCode());
    }

    private String nano() {
        return String.valueOf(System.nanoTime());
    }

    private ProductDtos.View createProduct(String name, int initStock, int threshold) {
        ProductDtos.View v = productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                "BAR-" + System.nanoTime(), "规格", "件",
                new BigDecimal("1.00"), new BigDecimal("10.00"), initStock, threshold));
        assertNotNull(v);
        return v;
    }
}
