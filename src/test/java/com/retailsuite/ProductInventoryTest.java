package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.product.entity.Product;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品与库存的核心行为测试。
 * 重点不在 CRUD，而在三件容易出事的地方：并发扣减不超卖、每次变动都有流水、门店之间互相看不见。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductInventoryTest {

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
        assertNotNull(store, "演示数据应当已经写入门店");
        storeId = store.getId();
    }

    @Test
    void 创建商品带期初库存会同时写入库存流水() {
        ProductDtos.View product = createProduct("测试商品-期初", "BAR-" + System.nanoTime(), 15, 5);

        assertEquals(15, product.stock());
        List<InventoryDtos.FlowView> flows = inventoryService.recentFlows(storeId, product.id(), 10);
        assertEquals(1, flows.size());
        assertEquals("IN", flows.get(0).type());
        assertEquals(0, flows.get(0).beforeStock());
        assertEquals(15, flows.get(0).afterStock());
        assertEquals("期初建库", flows.get(0).remark());
    }

    @Test
    void 并发扣减不会超卖() throws Exception {
        int stock = 10;
        int threads = 30;
        ProductDtos.View product = createProduct("测试商品-并发", "BAR-" + System.nanoTime(), stock, 2);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    // refType 用 MANUAL 而不是 SALE：这是合成压测流量，不是真实销售。
                    // （用 SALE 会污染"销售数量 vs 库存出库"的对账口径，报表测试会立刻发现差异）
                    inventoryService.decrease(storeId, product.id(), 1, "MANUAL", "TEST-CONCURRENT", "并发测试");
                    success.incrementAndGet();
                } catch (BizException e) {
                    if (e.getCode() == ErrorCode.STOCK_NOT_ENOUGH) {
                        refused.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(doneGate.await(30, TimeUnit.SECONDS), "并发扣减未在 30 秒内完成");
        pool.shutdownNow();

        Product latest = productMapper.selectById(product.id());
        assertEquals(stock, success.get(), "成功次数必须恰好等于库存：多一次就是超卖");
        assertEquals(threads - stock, refused.get(), "其余请求必须被明确拒绝（库存不足）");
        assertEquals(0, latest.getStock(), "最终库存必须归零");
        // 流水条数 = 成功次数，且每一条都能对上 before/after
        List<InventoryDtos.FlowView> flows = inventoryService.recentFlows(storeId, product.id(), 200);
        long outCount = flows.stream().filter(f -> "OUT".equals(f.type())).count();
        assertEquals(stock, outCount, "每次成功扣减都必须留下一条流水");
        // 流水不变量：after = before + quantity（quantity 带符号：入库为正、出库为负）
        assertTrue(flows.stream().allMatch(f -> f.afterStock() == f.beforeStock() + f.quantity()),
                "流水的 before/after 必须自洽：" + flows);
    }

    @Test
    void 库存不足时给出可读的业务异常() {
        ProductDtos.View product = createProduct("测试商品-不足", "BAR-" + System.nanoTime(), 1, 1);

        BizException error = assertThrows(BizException.class,
                () -> inventoryService.decrease(storeId, product.id(), 5, "MANUAL", "TEST", null));

        assertEquals(ErrorCode.STOCK_NOT_ENOUGH, error.getCode());
        assertTrue(error.getMessage().contains("库存不足"), error.getMessage());
        assertEquals(1, productMapper.selectById(product.id()).getStock(), "失败的扣减不能改动库存");
    }

    @Test
    void 盘点调整必须填原因且不能调成负数() {
        ProductDtos.View product = createProduct("测试商品-盘点", "BAR-" + System.nanoTime(), 10, 2);

        InventoryDtos.AdjustRequest ok = new InventoryDtos.AdjustRequest(product.id(), -4, "破损报废 4 件");
        InventoryDtos.FlowView flow = toFlowView(inventoryService.adjust(storeId, ok));
        assertEquals(6, flow.afterStock());
        assertEquals("ADJUST", flow.type());

        BizException negative = assertThrows(BizException.class, () -> inventoryService.adjust(storeId,
                new InventoryDtos.AdjustRequest(product.id(), -100, "清空")));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH, negative.getCode());

        BizException noReason = assertThrows(BizException.class, () -> inventoryService.adjust(storeId,
                new InventoryDtos.AdjustRequest(product.id(), 1, " ")));
        assertEquals(ErrorCode.BAD_REQUEST, noReason.getCode());
    }

    @Test
    void 修改商品不会改动库存() {
        ProductDtos.View product = createProduct("测试商品-改价", "BAR-" + System.nanoTime(), 12, 3);

        productService.update(storeId, product.id(), new ProductDtos.UpdateRequest(
                "测试商品-改价（新）", null, product.barcode(), "500ml", "瓶",
                new BigDecimal("1.50"), new BigDecimal("3.00"), 5, 1));

        Product latest = productMapper.selectById(product.id());
        assertEquals(12, latest.getStock(), "更新接口不含库存字段，库存必须保持不变");
        assertEquals(0, new BigDecimal("3.00").compareTo(latest.getSalePrice()));
    }

    @Test
    void 条码在同一门店内唯一() {
        String barcode = "BAR-DUP-" + System.nanoTime();
        createProduct("测试商品-条码A", barcode, 1, 1);

        BizException error = assertThrows(BizException.class,
                () -> createProduct("测试商品-条码B", barcode, 1, 1));

        assertEquals(ErrorCode.CONFLICT, error.getCode());
        assertTrue(error.getMessage().contains("条码"), error.getMessage());
    }

    @Test
    void 低库存商品会出现在预警列表里() {
        String name = "测试商品-预警-" + System.nanoTime();
        ProductDtos.View product = createProduct(name, "BAR-" + System.nanoTime(), 2, 10);

        List<InventoryDtos.LowStockItem> items = inventoryService.lowStockItems(storeId);

        assertTrue(items.stream().anyMatch(item -> item.productId().equals(product.id())),
                "库存 2 ≤ 阈值 10，必须出现在预警列表");
        assertTrue(product.lowStock(), "商品视图里也应带低库存标记，前端可直接高亮");
    }

    @Test
    void 门店隔离_别的门店看不到本店商品() {
        ProductDtos.View product = createProduct("测试商品-隔离", "BAR-" + System.nanoTime(), 5, 1);

        BizException error = assertThrows(BizException.class,
                () -> productService.detail(storeId + 999, product.id()));

        assertEquals(ErrorCode.NOT_FOUND, error.getCode(), "跨门店访问应表现为资源不存在，而不是泄漏资源存在性");
    }

    @Test
    void 分页查询支持关键字与低库存筛选() {
        String keyword = "分页专用-" + System.nanoTime();
        createProduct(keyword, "BAR-" + System.nanoTime(), 1, 10);
        createProduct(keyword, "BAR-" + System.nanoTime(), 50, 1);

        var page = productService.page(storeId, keyword, null, null, 1, 10);
        var lowStockPage = productService.page(storeId, keyword, null, true, 1, 10);

        assertEquals(2, page.total());
        assertEquals(1, lowStockPage.total(), "只有一个商品低于阈值");
        assertFalse(page.records().isEmpty());
    }

    private ProductDtos.View createProduct(String name, String barcode, int initStock, int lowStockThreshold) {
        return productService.create(storeId, new ProductDtos.CreateRequest(name, null, barcode, "500ml", "瓶",
                new BigDecimal("1.20"), new BigDecimal("2.00"), initStock, lowStockThreshold));
    }

    private InventoryDtos.FlowView toFlowView(com.retailsuite.inventory.entity.InventoryFlow flow) {
        return new InventoryDtos.FlowView(flow.getId(), flow.getProductId(), null, flow.getType(),
                flow.getQuantity(), flow.getBeforeStock(), flow.getAfterStock(), flow.getRefType(),
                flow.getRefNo(), flow.getRemark(), flow.getBatchId(), flow.getCreatedAt());
    }
}
