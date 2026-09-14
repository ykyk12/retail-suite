package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.product.dto.ProductDtos;
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
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第二轮回归：聚焦两类真问题。
 *
 * 1) 越权/IDOR：收银幂等快路径若只按 request_id 查询而不带 store_id，
 *    跨门店撞 requestId 时会把别家门店的订单详情回给本门店（跨租户数据泄露）。
 * 2) 金额一致性：订单有整单折扣时，退货按"行挂牌价"全额退、且没有封顶于"实收金额"，
 *    会出现"顾客只付了 150，却退给他 200"的超退（钱箱亏损）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SaleMoneyAndTenantTest {

    @Autowired
    private SaleService saleService;
    @Autowired
    private ProductService productService;
    @Autowired
    private SaleOrderMapper saleOrderMapper;
    @Autowired
    private StoreMapper storeMapper;

    private Long storeAId;

    @BeforeEach
    void setUp() {
        Store storeA = storeMapper.selectOne(new LambdaQueryWrapper<Store>().last("LIMIT 1"));
        assertNotNull(storeA);
        storeAId = storeA.getId();
    }

    // ---------------------------------------------------------------- Bug 1 越权

    @Test
    void 跨门店使用相同requestId_不得返回别家订单() {
        // 门店 A 先落一笔订单，requestId = R
        ProductDtos.View productA = createProduct(storeAId, "越权A-" + nano(), 10, "2.00");
        SalesDtos.View orderA = saleService.checkout(storeAId,
                checkoutRequest("IDOR-" + nano(),
                        java.util.List.of(new SalesDtos.ItemRequest(productA.id(), 1, null)), BigDecimal.ZERO));
        assertNotNull(orderA.id());

        // 另起一个门店 B
        Store storeB = newStore();
        ProductDtos.View productB = createProduct(storeB.getId(), "越权B-" + nano(), 10, "3.00");

        // 门店 B 用"同一个" requestId 来收银：必须报错，绝不能把门店 A 的订单返回
        BizException e = assertThrows(BizException.class, () -> saleService.checkout(storeB.getId(),
                checkoutRequest(orderA.requestId(),
                        java.util.List.of(new SalesDtos.ItemRequest(productB.id(), 1, null)), BigDecimal.ZERO)));

        // 跨门店撞 requestId 不是本店的幂等重试，应当作冲突拒绝
        assertEquals(ErrorCode.CONFLICT, e.getCode(), "跨门店 requestId 冲突必须拒绝，不能泄露别家订单");
        // 门店 B 不得因此产生任何订单
        long countB = saleOrderMapper.selectCount(new LambdaQueryWrapper<SaleOrder>()
                .eq(SaleOrder::getRequestId, orderA.requestId())
                .eq(SaleOrder::getStoreId, storeB.getId()));
        assertEquals(0, countB, "门店 B 不能因别家 requestId 生成订单");
    }

    // ---------------------------------------------------------------- Bug 2 退款金额

    @Test
    void 有整单折扣时整单退款_不超过实收金额() {
        // 单价 10，买 2 件 → 应收 20，折扣 5，实收 15
        ProductDtos.View product = createProduct(storeAId, "退款金额-" + nano(), 10, "10.00");
        SalesDtos.View order = saleService.checkout(storeAId, checkoutRequest("REF-" + nano(),
                java.util.List.of(new SalesDtos.ItemRequest(product.id(), 2, null)), new BigDecimal("5.00")));

        assertEquals(0, new BigDecimal("20.00").compareTo(order.totalAmount()));
        assertEquals(0, new BigDecimal("15.00").compareTo(order.payAmount()), "实收 = 应收 - 折扣");

        Long itemId = order.items().get(0).id();
        // 把两件全退完
        SalesDtos.View full = saleService.refund(storeAId, order.id(),
                new SalesDtos.RefundRequest(java.util.List.of(new SalesDtos.RefundItemRequest(itemId, 2)), "全退"));

        // 关键断言：累计退款必须 == 实收 15，而不是挂牌价合计 20（否则钱箱倒亏 5 元）
        assertEquals(0, new BigDecimal("15.00").compareTo(full.refundAmount()),
                "整单退款金额必须等于实收金额，不能按挂牌价超退");
        assertTrue(full.refundAmount().compareTo(order.payAmount()) <= 0, "退款不得超过实收");
    }

    @Test
    void 有折扣时部分退款_按比例分摊折扣() {
        // 单价 10，买 2 → 应收 20，折扣 5（折扣率 25%），实收 15
        ProductDtos.View product = createProduct(storeAId, "部分退-" + nano(), 10, "10.00");
        SalesDtos.View order = saleService.checkout(storeAId, checkoutRequest("PRT-" + nano(),
                java.util.List.of(new SalesDtos.ItemRequest(product.id(), 2, null)), new BigDecimal("5.00")));
        Long itemId = order.items().get(0).id();

        // 只退 1 件：挂牌 10，按 75% 实收比例退 7.5
        SalesDtos.View partial = saleService.refund(storeAId, order.id(),
                new SalesDtos.RefundRequest(java.util.List.of(new SalesDtos.RefundItemRequest(itemId, 1)), "退一件"));

        assertEquals(0, new BigDecimal("7.50").compareTo(partial.refundAmount()),
                "部分退款应按实收比例分摊折扣");
        // 剩余可退 = 15 - 7.5 = 7.5
        SalesDtos.View rest = saleService.refund(storeAId, order.id(),
                new SalesDtos.RefundRequest(java.util.List.of(new SalesDtos.RefundItemRequest(itemId, 1)), "再退一件"));
        assertEquals(0, new BigDecimal("15.00").compareTo(rest.refundAmount()),
                "两次退款累计必须等于实收 15");
    }

    // ---------------------------------------------------------------- 辅助

    @Test
    void 整单全免时退款金额为零不为负() {
        // 单价 10，买 1 → 应收 10，折扣 10（全免），实收 0
        ProductDtos.View product = createProduct(storeAId, "全免-" + nano(), 10, "10.00");
        SalesDtos.View order = saleService.checkout(storeAId, checkoutRequest("FREE-" + nano(),
                java.util.List.of(new SalesDtos.ItemRequest(product.id(), 1, null)), new BigDecimal("10.00")));
        assertEquals(0, new BigDecimal("0.00").compareTo(order.payAmount()), "全免实收应为 0");

        Long itemId = order.items().get(0).id();
        SalesDtos.View refunded = saleService.refund(storeAId, order.id(),
                new SalesDtos.RefundRequest(java.util.List.of(new SalesDtos.RefundItemRequest(itemId, 1)), "全免后退"));
        assertEquals(0, new BigDecimal("0.00").compareTo(refunded.refundAmount()),
                "实收为 0 时退款必须为 0，不能为负");
    }

    private String nano() {
        return String.valueOf(System.nanoTime());
    }

    private Store newStore() {
        Store store = new Store();
        store.setName("越权测试店-" + nano());
        store.setCode("TST-" + System.nanoTime());
        store.setAddress("测试地址");
        store.setPhone("028-00000000");
        store.setStatus(1);
        store.setCreatedAt(LocalDateTime.now());
        store.setUpdatedAt(LocalDateTime.now());
        store.setDeleted(0);
        storeMapper.insert(store);
        return store;
    }

    private SalesDtos.CheckoutRequest checkoutRequest(String requestId, java.util.List<SalesDtos.ItemRequest> items,
                                                      BigDecimal discount) {
        return new SalesDtos.CheckoutRequest(requestId, "散客", items, discount, SaleOrder.PAY_WECHAT, null);
    }

    private ProductDtos.View createProduct(Long storeId, String name, int initStock, String salePrice) {
        ProductDtos.View v = productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                "BAR-" + System.nanoTime(), "规格", "件",
                new BigDecimal("1.00"), new BigDecimal(salePrice), initStock, 3));
        assertNotNull(v);
        return v;
    }
}
