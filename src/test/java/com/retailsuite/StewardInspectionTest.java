package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.common.BizException;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.purchase.dto.PurchaseDtos;
import com.retailsuite.purchase.entity.PurchaseOrder;
import com.retailsuite.purchase.mapper.PurchaseOrderMapper;
import com.retailsuite.purchase.service.PurchaseService;
import com.retailsuite.sales.dto.SalesDtos;
import com.retailsuite.sales.entity.SaleOrder;
import com.retailsuite.sales.service.SaleService;
import com.retailsuite.security.AuthUser;
import com.retailsuite.steward.dto.StewardDtos;
import com.retailsuite.steward.entity.StewardReport;
import com.retailsuite.steward.mapper.StewardReportMapper;
import com.retailsuite.steward.service.StewardActionService;
import com.retailsuite.steward.service.StewardAnalysisService;
import com.retailsuite.steward.service.StewardInspectionService;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管家主动巡检日报（M3）。
 *
 * 验的是门店真正关心的四件事：
 * 1) 过期/临期批次有没有被主动发现（而不是等顾客投诉）；
 * 2) 补货建议能不能一键变成采购草稿，且**草稿不动库存**；
 * 3) 没有写权限的账号点不动这个按钮（审计与权限走 Agent 同一条链路）；
 * 4) 同一天重复巡检是覆盖，不会刷出一堆重复报告。
 */
@SpringBootTest
@ActiveProfiles("test")
class StewardInspectionTest {

    @Autowired
    private StewardInspectionService inspectionService;
    @Autowired
    private StewardActionService actionService;
    @Autowired
    private StewardAnalysisService analysisService;
    @Autowired
    private StewardReportMapper reportMapper;
    @Autowired
    private ProductService productService;
    @Autowired
    private PurchaseService purchaseService;
    @Autowired
    private PurchaseOrderMapper purchaseOrderMapper;
    @Autowired
    private SaleService saleService;
    @Autowired
    private StoreMapper storeMapper;

    private Long storeId;
    private AuthUser admin;
    private AuthUser cashier;

    @BeforeEach
    void setUp() {
        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>().last("LIMIT 1"));
        assertNotNull(store);
        storeId = store.getId();
        admin = new AuthUser(9201L, storeId, "steward-admin", "巡检测试店长", Set.of("ADMIN"),
                Set.of("report:read", "inventory:read", "inventory:loss", "product:read",
                        "purchase:read", "purchase:write", "ai:use"));
        // 收银员有 ai:use 但没有 purchase:write：能看日报，不能把建议直接变成采购草稿
        cashier = new AuthUser(9202L, storeId, "steward-cashier", "巡检测试收银员", Set.of("CASHIER"),
                Set.of("report:read", "inventory:read", "product:read", "ai:use"));
    }

    @Test
    void 巡检能发现过期与临期批次并算出压货金额() {
        ProductDtos.View expiredProduct = createPerishable("巡检测试过期酸奶", 7, "9.00", "5.00");
        ProductDtos.View expiringProduct = createPerishable("巡检测试临期面包", 30, "12.00", "7.00");

        // 生产日期在 10 天前、保质期 7 天 → 已过期 3 天
        inbound(expiredProduct.id(), 6, new BigDecimal("5.00"), LocalDate.now().minusDays(10), 7);
        // 生产日期在 27 天前、保质期 30 天 → 还剩 3 天到期
        inbound(expiringProduct.id(), 4, new BigDecimal("7.00"), LocalDate.now().minusDays(27), 30);

        StewardDtos.ReportView report = inspectionService.inspect(storeId, StewardInspectionService.SOURCE_MANUAL);

        StewardDtos.Finding expired = finding(report, "EXPIRED_BATCH");
        assertEquals("HIGH", expired.severity(), "过期批次必须标成需立即处理");
        assertTrue(expired.title().contains("过期"), expired.title());
        // 报告是门店级汇总：测试库是共享的，别的用例也可能留下过期批次，
        // 所以这里校验"至少覆盖到我们这批"（数量与金额都不小于本用例造的数据）
        assertTrue((int) expired.metrics().get("batchCount") >= 1);
        assertTrue(((Number) expired.metrics().get("quantity")).longValue() >= 6);
        assertTrue(decimal(expired.metrics(), "costAmount").compareTo(new BigDecimal("30.00")) >= 0,
                "过期压货金额至少要包含本用例的 6 件 × 5 元");

        StewardDtos.Finding expiring = finding(report, "EXPIRING_BATCH");
        assertTrue((int) expiring.metrics().get("batchCount") >= 1);
        assertTrue(report.headline().contains("过期批次"), "总结里要出现发现类型：" + report.headline());
        assertTrue(report.highCount() >= 1, "至少有过期批次这一条需立即处理");
    }

    @Test
    void 补货建议可以一键转采购草稿且不动库存() {
        ProductDtos.View product = createPerishable("巡检测试补货商品", 365, "15.00", "10.00");
        // 卖过 7 件 → 有销速；库存降到阈值以下 → 会被建议补货
        inbound(product.id(), 20, new BigDecimal("10.00"), LocalDate.now().minusDays(5), 365);
        sell(product, 12);

        StewardDtos.ReportView report = inspectionService.inspect(storeId, StewardInspectionService.SOURCE_MANUAL);
        StewardDtos.Finding reorder = finding(report, "REORDER");
        assertEquals("WARN", reorder.severity());
        StewardDtos.Action action = reorder.actions().stream()
                .filter(a -> StewardActionService.ACTION_PURCHASE_DRAFT.equals(a.type()))
                .findFirst().orElseThrow(() -> new AssertionError("补货建议应带一键转草稿动作"));
        assertTrue(action.executable());
        assertTrue(action.hint().contains("草稿"), "动作提示必须说清只生成草稿：" + action.hint());

        int stockBefore = productService.detail(storeId, product.id()).stock();
        StewardDtos.ActionResult result = actionService.execute(admin, storeId, report.id(),
                "REORDER", StewardActionService.ACTION_PURCHASE_DRAFT);

        assertTrue(result.message().contains("草稿"), result.message());
        assertEquals("DRAFT", result.data().get("status"), "只会生成草稿态的采购单");
        assertEquals(stockBefore, productService.detail(storeId, product.id()).stock(),
                "生成草稿不能改库存——库存只在人工确认入库后才变");
        assertEquals(1, purchaseOrderMapper.selectCount(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getStoreId, storeId)
                .eq(PurchaseOrder::getStatus, PurchaseOrder.STATUS_DRAFT)
                .eq(PurchaseOrder::getRemark, "由管家巡检日报（发现：REORDER）生成草稿")));
    }

    @Test
    void 没有进货权限的账号点不动一键转草稿() {
        ProductDtos.View product = createPerishable("巡检测试越权商品", 365, "8.00", "4.00");
        inbound(product.id(), 30, new BigDecimal("4.00"), LocalDate.now().minusDays(2), 365);
        sell(product, 25);

        StewardDtos.ReportView report = inspectionService.inspect(storeId, StewardInspectionService.SOURCE_MANUAL);
        finding(report, "REORDER");

        BizException error = assertThrows(BizException.class, () -> actionService.execute(
                cashier, storeId, report.id(), "REORDER", StewardActionService.ACTION_PURCHASE_DRAFT));
        assertTrue(error.getMessage().contains("权限"), "应收敛成权限错误：" + error.getMessage());
    }

    @Test
    void 同一天重复巡检是覆盖而不是新增() {
        List<StewardReport> before = reportMapper.selectList(new LambdaQueryWrapper<StewardReport>()
                .eq(StewardReport::getStoreId, storeId)
                .eq(StewardReport::getReportDate, LocalDate.now()));
        assertTrue(before.size() <= 1, "唯一键保证同门店同一天最多一条");

        Long firstId = inspectionService.inspect(storeId, StewardInspectionService.SOURCE_SCHEDULED).id();
        Long secondId = inspectionService.inspect(storeId, StewardInspectionService.SOURCE_MANUAL).id();

        assertEquals(firstId, secondId, "第二次巡检应覆盖同一行，而不是插一条新的");
        assertEquals(1, reportMapper.selectCount(new LambdaQueryWrapper<StewardReport>()
                        .eq(StewardReport::getStoreId, storeId)
                        .eq(StewardReport::getReportDate, LocalDate.now())),
                "同一天只应有一份日报（唯一键 store_id + report_date）");
        assertEquals(StewardInspectionService.SOURCE_MANUAL,
                inspectionService.latest(storeId).source(), "后一次巡检覆盖前一次的内容");
    }

    @Test
    void 巡检能发现滞销商品与毛利异常() {
        ProductDtos.View slow = createPerishable("巡检测试滞销商品", 365, "20.00", "12.00");
        inbound(slow.id(), 15, new BigDecimal("12.00"), LocalDate.now().minusDays(60), 365);

        // 进价 10 元、售价 9 元且卖出去 → 负毛利
        ProductDtos.View loss = createPerishable("巡检测试负毛利商品", 365, "9.00", "10.00");
        inbound(loss.id(), 20, new BigDecimal("10.00"), LocalDate.now().minusDays(3), 365);
        sell(loss, 5);

        StewardDtos.ReportView report = inspectionService.inspect(storeId, StewardInspectionService.SOURCE_MANUAL);

        StewardDtos.Finding slowMover = finding(report, "SLOW_MOVER");
        assertTrue(slowMover.title().contains("滞销"), slowMover.title());
        assertTrue(decimal(slowMover.metrics(), "tiedUpAmount").compareTo(BigDecimal.ZERO) > 0);

        // 报告明细有条数上限（保留压货金额最大的前 N 条）且测试库是共享的，
        // 因此"是否识别出这个滞销商品"在分析层断言，"报告里有没有滞销这一项"在报告层断言。
        List<StewardAnalysisService.SlowMover> movers = analysisService.slowMovers(storeId, 30);
        assertTrue(movers.stream().anyMatch(m -> m.productName().equals("巡检测试滞销商品")),
                "滞销分析应识别出从未售出的商品：" + movers.stream().map(StewardAnalysisService.SlowMover::productName).toList());

        StewardDtos.Finding margin = finding(report, "MARGIN_ANOMALY");
        assertTrue(margin.title().contains("毛利"), margin.title());
        assertTrue(margin.detail().contains("巡检测试负毛利商品"), margin.detail());
        assertTrue(margin.detail().contains("负毛利"), "负毛利要点名，不能混在低毛利里：" + margin.detail());
        assertTrue(analysisService.marginAnomalies(storeId, 30, BigDecimal.TEN).stream()
                        .anyMatch(a -> a.productName().equals("巡检测试负毛利商品")
                                && a.grossProfit().compareTo(BigDecimal.ZERO) < 0),
                "负毛利商品必须被判为异常");
    }

    @Test
    void 报告详情与历史列表按门店隔离() {
        StewardDtos.ReportView report = inspectionService.inspect(storeId, StewardInspectionService.SOURCE_MANUAL);

        assertEquals(report.id(), inspectionService.find(storeId, report.id()).id());
        assertTrue(inspectionService.recent(storeId, 5).stream().anyMatch(r -> r.id().equals(report.id())));
        assertFalse(inspectionService.recent(storeId, 5).isEmpty());

        // 换个门店 id 去读同一份报告：必须当作不存在（经营数据不能跨店看）
        BizException error = assertThrows(BizException.class,
                () -> inspectionService.find(storeId + 99999L, report.id()));
        assertTrue(error.getMessage().contains("不存在"), error.getMessage());
    }

    // ------------------------------------------------------------------ 工具

    private StewardDtos.Finding finding(StewardDtos.ReportView report, String code) {
        return report.findings().stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("报告里应包含 " + code + "，实际：" + report.headline()));
    }

    /**
     * 从 metrics 里取数值。
     *
     * metrics 是 JSON 往返的通用容器（落库 → 读回），JsonNode 到 Map&lt;String,Object&gt; 之后
     * 小数会是 Double、整数会是 Integer——所以按 {@link Number} 语义比较，不假设它是 BigDecimal。
     */
    private BigDecimal decimal(java.util.Map<String, Object> metrics, String key) {
        Object raw = metrics.get(key);
        assertNotNull(raw, "metrics 里应有 " + key + "：" + metrics);
        return new BigDecimal(String.valueOf(raw));
    }

    private ProductDtos.View createPerishable(String name, int shelfLifeDays, String salePrice, String purchasePrice) {
        return productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                "STW-" + UUID.randomUUID().toString().substring(0, 8), "规格", "件",
                new BigDecimal(purchasePrice), new BigDecimal(salePrice), 0, 10, shelfLifeDays));
    }

    private void inbound(Long productId, int quantity, BigDecimal unitCost, LocalDate productionDate, int shelfLifeDays) {
        PurchaseDtos.View order = purchaseService.create(storeId, new PurchaseDtos.CreateRequest(
                "巡检测试供应商", "巡检造数据",
                List.of(new PurchaseDtos.ItemRequest(productId, quantity, unitCost, productionDate, shelfLifeDays))));
        purchaseService.confirm(storeId, order.id());
    }

    private void sell(ProductDtos.View product, int quantity) {
        saleService.checkout(storeId, new SalesDtos.CheckoutRequest("STW-" + UUID.randomUUID(),
                "散客", List.of(new SalesDtos.ItemRequest(product.id(), quantity, null)),
                BigDecimal.ZERO, SaleOrder.PAY_CASH, null));
    }
}
