package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.sales.dto.SalesDtos;
import com.retailsuite.sales.entity.SaleOrder;
import com.retailsuite.sales.mapper.SaleOrderMapper;
import com.retailsuite.sales.service.SaleService;
import com.retailsuite.store.entity.Store;
import com.retailsuite.store.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收银链路：金额计算、幂等、库存不足整笔回滚、退货回补。
 * 其中"并发同一 requestId 只产生一笔订单"是收银系统最实际、也最容易被忽略的保障。
 */
@SpringBootTest
@ActiveProfiles("test")
class SaleOrderFlowTest {

    @Autowired
    private SaleService saleService;
    @Autowired
    private ProductService productService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private SaleOrderMapper saleOrderMapper;
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
    void 结算扣库存并正确计算金额() {
        ProductDtos.View product = createProduct("收银测试商品A", 20, "2.00");

        SalesDtos.View order = saleService.checkout(storeId, checkoutRequest(newId(),
                List.of(new SalesDtos.ItemRequest(product.id(), 3, null)), new BigDecimal("1.00")));

        assertEquals(SaleOrder.STATUS_PAID, order.status());
        assertEquals(0, new BigDecimal("6.00").compareTo(order.totalAmount()));
        assertEquals(0, new BigDecimal("1.00").compareTo(order.discountAmount()));
        assertEquals(0, new BigDecimal("5.00").compareTo(order.payAmount()), "实收 = 应收 − 折扣");
        assertEquals(17, productMapper.selectById(product.id()).getStock());
        assertTrue(order.duplicated() == false);

        List<InventoryDtos.FlowView> flows = inventoryService.recentFlows(storeId, product.id(), 5);
        assertTrue(flows.stream().anyMatch(f -> "SALE".equals(f.refType()) && f.quantity() == -3));
    }

    @Test
    void 同一requestId重复提交只产生一笔订单且只扣一次库存() {
        ProductDtos.View product = createProduct("收银测试商品B", 10, "3.00");
        String requestId = newId();
        SalesDtos.CheckoutRequest request = checkoutRequest(requestId,
                List.of(new SalesDtos.ItemRequest(product.id(), 2, null)), BigDecimal.ZERO);

        SalesDtos.View first = saleService.checkout(storeId, request);
        SalesDtos.View second = saleService.checkout(storeId, request);

        assertEquals(first.orderNo(), second.orderNo(), "重复提交必须返回首次订单");
        assertTrue(second.duplicated(), "第二次必须标记为幂等命中");
        assertEquals(8, productMapper.selectById(product.id()).getStock(), "库存只能扣一次");
        assertEquals(1, countOrdersByRequestId(requestId));
    }

    @Test
    void 并发同一requestId只会落一笔订单() throws Exception {
        ProductDtos.View product = createProduct("收银测试商品C", 50, "1.50");
        String requestId = newId();
        SalesDtos.CheckoutRequest request = checkoutRequest(requestId,
                List.of(new SalesDtos.ItemRequest(product.id(), 4, null)), BigDecimal.ZERO);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        Set<String> orderNos = new HashSet<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    SalesDtos.View view = saleService.checkout(storeId, request);
                    synchronized (orderNos) {
                        orderNos.add(view.orderNo());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(doneGate.await(30, TimeUnit.SECONDS), "并发结算未在 30 秒内完成");
        pool.shutdownNow();

        assertEquals(1, countOrdersByRequestId(requestId), "唯一索引必须保证只落一笔订单");
        assertEquals(1, orderNos.size(), "所有线程返回的订单号必须一致：" + orderNos);
        assertEquals(46, productMapper.selectById(product.id()).getStock(), "库存只能扣一次（4 件）");
    }

    @Test
    void 库存不足时整笔回滚_不留下订单与流水() {
        ProductDtos.View product = createProduct("收银测试商品D", 2, "5.00");
        String requestId = newId();

        BizException error = assertThrows(BizException.class, () -> saleService.checkout(storeId, checkoutRequest(
                requestId, List.of(new SalesDtos.ItemRequest(product.id(), 5, null)), BigDecimal.ZERO)));

        assertEquals(ErrorCode.STOCK_NOT_ENOUGH, error.getCode());
        assertEquals(0, countOrdersByRequestId(requestId), "失败的结算不能留下订单");
        assertEquals(2, productMapper.selectById(product.id()).getStock(), "失败的结算不能扣库存");
        assertTrue(inventoryService.recentFlows(storeId, product.id(), 10).stream()
                .noneMatch(f -> "SALE".equals(f.refType())), "失败的结算不能留下出库流水");
    }

    @Test
    void 折扣不能大于应收金额() {
        ProductDtos.View product = createProduct("收银测试商品E", 5, "2.00");

        BizException error = assertThrows(BizException.class, () -> saleService.checkout(storeId, checkoutRequest(
                newId(), List.of(new SalesDtos.ItemRequest(product.id(), 1, null)), new BigDecimal("99.00"))));

        assertEquals(ErrorCode.BAD_REQUEST, error.getCode());
        assertEquals(5, productMapper.selectById(product.id()).getStock());
    }

    @Test
    void 部分退货回补库存_全部退完状态变为整单退货() {
        ProductDtos.View product = createProduct("收银测试商品F", 10, "4.00");
        SalesDtos.View order = saleService.checkout(storeId, checkoutRequest(newId(),
                List.of(new SalesDtos.ItemRequest(product.id(), 4, null)), BigDecimal.ZERO));
        Long itemId = order.items().get(0).id();

        SalesDtos.View partial = saleService.refund(storeId, order.id(),
                new SalesDtos.RefundRequest(List.of(new SalesDtos.RefundItemRequest(itemId, 1)), "顾客只退一件"));

        assertEquals(SaleOrder.STATUS_PARTIAL_REFUNDED, partial.status());
        assertEquals(1, partial.items().get(0).refundedQuantity());
        assertEquals(0, new BigDecimal("4.00").compareTo(partial.refundAmount()));
        assertEquals(7, productMapper.selectById(product.id()).getStock(), "退 1 件回补 1 件");

        SalesDtos.View full = saleService.refund(storeId, order.id(),
                new SalesDtos.RefundRequest(List.of(new SalesDtos.RefundItemRequest(itemId, 3)), "剩余全退"));

        assertEquals(SaleOrder.STATUS_REFUNDED, full.status());
        assertEquals(0, new BigDecimal("16.00").compareTo(full.refundAmount()));
        assertEquals(10, productMapper.selectById(product.id()).getStock(), "全部退完库存回到 10");

        List<InventoryDtos.FlowView> flows = inventoryService.recentFlows(storeId, product.id(), 10);
        long refundFlows = flows.stream().filter(f -> "REFUND".equals(f.refType())).count();
        assertEquals(2, refundFlows, "两次退货各写一条回补流水");
    }

    @Test
    void 退货数量不能超过可退数量() {
        ProductDtos.View product = createProduct("收银测试商品G", 10, "1.00");
        SalesDtos.View order = saleService.checkout(storeId, checkoutRequest(newId(),
                List.of(new SalesDtos.ItemRequest(product.id(), 2, null)), BigDecimal.ZERO));
        Long itemId = order.items().get(0).id();

        BizException error = assertThrows(BizException.class, () -> saleService.refund(storeId, order.id(),
                new SalesDtos.RefundRequest(List.of(new SalesDtos.RefundItemRequest(itemId, 3)), "退多了")));

        assertEquals(ErrorCode.CONFLICT, error.getCode());
        assertEquals(8, productMapper.selectById(product.id()).getStock(), "被拒的退货不能回补库存");
    }

    private long countOrdersByRequestId(String requestId) {
        return saleOrderMapper.selectCount(new LambdaQueryWrapper<SaleOrder>()
                .eq(SaleOrder::getRequestId, requestId));
    }

    private String newId() {
        return "REQ-" + java.util.UUID.randomUUID();
    }

    private SalesDtos.CheckoutRequest checkoutRequest(String requestId, List<SalesDtos.ItemRequest> items,
                                                      BigDecimal discount) {
        return new SalesDtos.CheckoutRequest(requestId, "散客", items, discount, SaleOrder.PAY_WECHAT, null);
    }

    private ProductDtos.View createProduct(String name, int initStock, String salePrice) {
        return productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                "BAR-" + System.nanoTime(), "规格", "件",
                new BigDecimal("1.00"), new BigDecimal(salePrice), initStock, 3));
    }
}
