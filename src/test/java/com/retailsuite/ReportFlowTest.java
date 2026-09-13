package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.excel.ExcelExporter;
import com.retailsuite.report.mapper.DailySalesSummaryMapper;
import com.retailsuite.report.service.ReportService;
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

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表链路：金额与毛利口径、汇总物化幂等、TOP 商品、对账差异、Excel 导出。
 * 对账那条用例刻意"人为制造不一致"（直接扣库存但不落订单），验证对账真的能发现问题——
 * 只会报"一切正常"的对账等于没有对账。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportFlowTest {

    @Autowired
    private ReportService reportService;
    @Autowired
    private SaleService saleService;
    @Autowired
    private ProductService productService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private DailySalesSummaryMapper summaryMapper;
    @Autowired
    private ExcelExporter excelExporter;
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
    void 概览金额与毛利口径正确() {
        ProductDtos.View product = createProduct("报表商品A", 50, "10.00", "4.00");
        saleService.checkout(storeId, request(List.of(new SalesDtos.ItemRequest(product.id(), 2, null)),
                BigDecimal.ZERO));
        saleService.checkout(storeId, request(List.of(new SalesDtos.ItemRequest(product.id(), 1, null)),
                new BigDecimal("2.00")));

        ReportDtos.Overview overview = reportService.overview(storeId, LocalDate.now());

        assertEquals(2, overview.orderCount());
        assertEquals(3, overview.itemCount());
        assertEquals(0, new BigDecimal("30.00").compareTo(overview.salesAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(overview.refundAmount()));
        assertEquals(0, new BigDecimal("30.00").compareTo(overview.netAmount()));
        // 毛利 = 销售额 30 − 成本 12 = 18
        assertEquals(0, new BigDecimal("18.00").compareTo(overview.grossProfit()));
        assertEquals(0, new BigDecimal("15.00").compareTo(overview.avgOrderAmount()));
    }

    @Test
    void 退货后毛利要把退款成本算回去() {
        ProductDtos.View product = createProduct("报表商品B", 20, "10.00", "4.00");
        SalesDtos.View order = saleService.checkout(storeId, request(
                List.of(new SalesDtos.ItemRequest(product.id(), 2, null)), BigDecimal.ZERO));
        saleService.refund(storeId, order.id(), new SalesDtos.RefundRequest(
                List.of(new SalesDtos.RefundItemRequest(order.items().get(0).id(), 1)), "退 1 件"));

        ReportDtos.Overview overview = reportService.overview(storeId, LocalDate.now());

        assertEquals(0, new BigDecimal("20.00").compareTo(overview.salesAmount()));
        assertEquals(0, new BigDecimal("10.00").compareTo(overview.refundAmount()));
        assertEquals(0, new BigDecimal("10.00").compareTo(overview.netAmount()));
        // 毛利 = (20 − 8) − (10 − 4) = 6
        assertEquals(0, new BigDecimal("6.00").compareTo(overview.grossProfit()), "退款必须把对应成本也扣回来");
    }

    @Test
    void 日汇总物化幂等且数值与实时一致() {
        ProductDtos.View product = createProduct("报表商品C", 30, "5.00", "2.00");
        saleService.checkout(storeId, request(List.of(new SalesDtos.ItemRequest(product.id(), 4, null)),
                BigDecimal.ZERO));
        LocalDate today = LocalDate.now();

        ReportDtos.DailyRow first = reportService.rebuildDailySummary(storeId, today);
        ReportDtos.DailyRow second = reportService.rebuildDailySummary(storeId, today);

        assertEquals(first.salesAmount(), second.salesAmount());
        assertEquals(first.netAmount(), second.netAmount());
        assertEquals(1, summaryMapper.selectCount(new LambdaQueryWrapper<com.retailsuite.report.entity.DailySalesSummary>()
                .eq(com.retailsuite.report.entity.DailySalesSummary::getStoreId, storeId)
                .eq(com.retailsuite.report.entity.DailySalesSummary::getSummaryDate, today)),
                "同一天只能有一行汇总（唯一索引兜底 + 先查后写）");

        List<ReportDtos.DailyRow> summaries = reportService.dailySummaries(storeId, today, today);
        assertEquals(1, summaries.size());
        assertEquals(0, new BigDecimal("20.00").compareTo(summaries.get(0).salesAmount()));
        ReportDtos.Overview live = reportService.overview(storeId, today);
        assertEquals(0, live.netAmount().compareTo(summaries.get(0).netAmount()), "汇总口径与实时口径必须一致");
    }

    @Test
    void TOP商品按销售额排序并给出毛利() {
        ProductDtos.View cheap = createProduct("报表商品D-低", 100, "2.00", "1.00");
        ProductDtos.View expensive = createProduct("报表商品E-高", 100, "50.00", "20.00");
        saleService.checkout(storeId, request(List.of(new SalesDtos.ItemRequest(cheap.id(), 5, null)),
                BigDecimal.ZERO));
        saleService.checkout(storeId, request(List.of(new SalesDtos.ItemRequest(expensive.id(), 4, null)),
                BigDecimal.ZERO));

        List<ReportDtos.TopProduct> top = reportService.topProducts(storeId, LocalDate.now(), LocalDate.now(), 10);

        assertFalse(top.isEmpty());
        assertEquals(expensive.id(), top.get(0).productId(), "销售额高的排前面");
        assertEquals(0, new BigDecimal("200.00").compareTo(top.get(0).amount()));
        assertEquals(0, new BigDecimal("120.00").compareTo(top.get(0).grossProfit()), "毛利 = 200 − 80");
    }

    @Test
    void 对账应一致_并在人为制造差异时报警() {
        ProductDtos.View product = createProduct("报表商品F", 30, "6.00", "3.00");
        saleService.checkout(storeId, request(List.of(new SalesDtos.ItemRequest(product.id(), 2, null)),
                BigDecimal.ZERO));

        ReportDtos.Reconcile consistent = reportService.reconcile(storeId, LocalDate.now());
        assertTrue(consistent.consistent(), "正常销售后不应有差异：" + consistent.diffs());
        assertTrue(consistent.checkedProducts() >= 1);

        // 人为制造差异：直接扣库存写流水，但不产生订单（模拟"绕过收银动了库存"）
        inventoryService.decrease(storeId, product.id(), 3, "SALE", "MANUAL-HACK", "测试制造的差异");

        ReportDtos.Reconcile broken = reportService.reconcile(storeId, LocalDate.now());
        assertFalse(broken.consistent(), "绕过收银的库存变动必须被对账发现");
        assertTrue(broken.diffs().stream().anyMatch(row -> row.productId().equals(product.id())
                && row.diff() == -3), "差异应为 -3（库存多扣了 3 件）：" + broken.diffs());
    }

    @Test
    void Excel导出能生成真实xlsx字节流() throws Exception {
        ProductDtos.View product = createProduct("报表商品G", 10, "3.00", "1.00");
        saleService.checkout(storeId, request(List.of(new SalesDtos.ItemRequest(product.id(), 1, null)),
                BigDecimal.ZERO));

        List<List<Object>> rows = reportService.exportDailyRows(storeId, LocalDate.now(), LocalDate.now());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelExporter.write(out, "日报", List.of("日期", "订单数", "商品件数", "销售额", "退款额", "净销售额", "毛利"), rows);

        byte[] bytes = out.toByteArray();
        assertTrue(bytes.length > 0, "导出的 xlsx 不能是空文件");
        // xlsx 本质是 zip：魔数为 PK(0x50 0x4B)，用它验证"确实写出了合法工作簿"
        assertEquals(0x50, bytes[0] & 0xFF);
        assertEquals(0x4B, bytes[1] & 0xFF);
        assertFalse(rows.isEmpty());
    }

    private SalesDtos.CheckoutRequest request(List<SalesDtos.ItemRequest> items, BigDecimal discount) {
        return new SalesDtos.CheckoutRequest("REQ-" + java.util.UUID.randomUUID(), "散客", items,
                discount, SaleOrder.PAY_CASH, null);
    }

    private ProductDtos.View createProduct(String name, int initStock, String salePrice, String purchasePrice) {
        return productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                "BAR-" + System.nanoTime(), "规格", "件",
                new BigDecimal(purchasePrice), new BigDecimal(salePrice), initStock, 3));
    }
}
