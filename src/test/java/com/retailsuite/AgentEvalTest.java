package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.agent.dto.AgentDtos;
import com.retailsuite.agent.runtime.AgentRuntime;
import com.retailsuite.agent.runtime.AgentToolExecutor;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.purchase.dto.PurchaseDtos;
import com.retailsuite.purchase.service.PurchaseService;
import com.retailsuite.sales.dto.SalesDtos;
import com.retailsuite.sales.entity.SaleOrder;
import com.retailsuite.sales.service.SaleService;
import com.retailsuite.security.AuthUser;
import com.retailsuite.store.entity.Store;
import com.retailsuite.store.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管家 Agent 的**规则兜底评测集**（离线路径）。
 *
 * 为什么要有它：门店断网 / 模型超配额时走的是规则路由，这条路径没有大模型兜底，
 * 错一次就是一次真实答非所问。所以把"门店最常见的问法"整理成一张表，
 * 逐条断言"路由到了哪个工具、有没有答到点上、该拒绝的有没有拒绝"，
 * 改动规则时这张表就是回归网（表驱动，新增一行即可扩展）。
 *
 * 评测维度：
 * 1) 意图命中：问法 → 期望工具（不同说法要能归到同一个意图）
 * 2) 能力边界：答不了的问题必须说清"我能做什么"，不能编
 * 3) 安全红线：规则路径**永远不能自动下单**（写操作只能由人显式确认）
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentEvalTest {

    @Autowired
    private AgentRuntime agentRuntime;
    @Autowired
    private AgentToolExecutor toolExecutor;
    @Autowired
    private ProductService productService;
    @Autowired
    private PurchaseService purchaseService;
    @Autowired
    private SaleService saleService;
    @Autowired
    private StoreMapper storeMapper;

    private Long storeId;
    private AuthUser admin;
    private String knownProductName;

    /** 评测样例：问法 → 期望工具；expectedTool 为 null 表示"应当拒绝并说明能力边界"。 */
    private record EvalCase(String question, String expectedTool, String mustContain, String description) {
    }

    @BeforeEach
    void setUp() {
        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>().last("LIMIT 1"));
        assertNotNull(store);
        storeId = store.getId();
        admin = new AuthUser(9301L, storeId, "eval-admin", "评测店长", Set.of("ADMIN"),
                Set.of("report:read", "inventory:read", "inventory:loss", "product:read",
                        "purchase:read", "purchase:write", "ai:use"));

        // 评测需要一个"真实存在且卖得动"的商品，否则单品画像类问法会落到"没找到商品"。
        // 名称带随机后缀：同一 Spring 上下文里多个用例会各建一次，重名会让单品画像正确地
        // 拒绝成"匹配到多个商品"，从而让评测失败看起来像被测逻辑的问题（实际是夹具的问题）。
        ProductDtos.View product = createProduct("评测用例矿泉水" + UUID.randomUUID().toString().substring(0, 6),
                30, "3.00", "1.50");
        knownProductName = product.name();
        purchaseService.confirm(storeId, purchaseService.create(storeId, new PurchaseDtos.CreateRequest(
                "评测供应商", "评测造数据",
                List.of(new PurchaseDtos.ItemRequest(product.id(), 40, new BigDecimal("1.50"),
                        LocalDate.now().minusDays(2), 365)))).id());
        saleService.checkout(storeId, new SalesDtos.CheckoutRequest("EVAL-" + UUID.randomUUID(), "散客",
                List.of(new SalesDtos.ItemRequest(product.id(), 6, null)),
                BigDecimal.ZERO, SaleOrder.PAY_CASH, null));
    }

    /** 评测用例表：改动规则路由时，这一张表必须全绿。 */
    private List<EvalCase> evalCases() {
        List<EvalCase> cases = new ArrayList<>();
        // ---- 经营概况 ----
        cases.add(new EvalCase("今天卖了多少", "sales_summary", "营业额", "口语化营业额"));
        cases.add(new EvalCase("昨天营业额是多少", "sales_summary", "营业额", "昨天口径"));
        cases.add(new EvalCase("本周经营情况怎么样", "sales_summary", "营业额", "本周口径"));
        cases.add(new EvalCase("今天客单价多少", "sales_summary", "客单价", "客单价"));
        // ---- 排行 ----
        cases.add(new EvalCase("最好卖的商品是哪个", "sales_ranking", "排行", "畅销排行"));
        cases.add(new EvalCase("本周卖得最好的商品", "sales_ranking", "排行", "本周畅销"));
        cases.add(new EvalCase("按毛利排行看看", "sales_ranking", "毛利", "毛利排行（不能被当成商品名）"));
        // ---- 单品画像 ----
        cases.add(new EvalCase(knownProductName + " 还有多少库存", "product_profile", "库存", "按名称查单品"));
        cases.add(new EvalCase(knownProductName + " 的进价和售价是多少", "product_profile", "进价", "进价售价"));
        // ---- 保质期 ----
        cases.add(new EvalCase("哪些商品快过期了", "expiry_alert", "过期", "临期/过期"));
        cases.add(new EvalCase("有没有临期批次", "expiry_alert", "临期", "临期同义词"));
        cases.add(new EvalCase("这个商品还能卖多久", "expiry_alert", "保质期", "效期问法"));
        // ---- 缺货与补货 ----
        cases.add(new EvalCase("哪些商品要断货了", "stock_health", "库存", "断货风险"));
        cases.add(new EvalCase("库存够不够卖", "stock_health", "库存", "库存健康"));
        cases.add(new EvalCase("要不要进货", "reorder_suggestion", "补货", "补货建议"));
        cases.add(new EvalCase("这个商品该进多少", "reorder_suggestion", "补货", "补货量"));
        // ---- 滞销与利润 ----
        cases.add(new EvalCase("哪些商品卖不动", "slow_movers", "滞销", "滞销"));
        cases.add(new EvalCase("有没有亏本卖的商品", "margin_alert", "毛利", "负毛利"));
        cases.add(new EvalCase("毛利率低的商品有哪些", "margin_alert", "毛利", "低毛利"));
        // ---- 对账 ----
        cases.add(new EvalCase("今天对账有差异吗", "reconcile", "对账", "账实对账"));
        // ---- 能力边界：答不了要说清能做什么 ----
        cases.add(new EvalCase("帮我预测下个月的销量", null, "我可以回答", "预测类问题要拒绝"));
        cases.add(new EvalCase("帮我写一首诗", null, "我可以回答", "完全无关的问题要拒绝"));
        return cases;
    }

    @Test
    void 规则兜底评测集_全部用例命中期望工具或正确拒绝() {
        List<String> failures = new ArrayList<>();
        int passed = 0;

        for (EvalCase evalCase : evalCases()) {
            AgentDtos.ChatResponse response = agentRuntime.chat(admin, storeId,
                    new AgentDtos.ChatRequest(null, evalCase.question()));
            String tools = String.valueOf(response.toolsUsed());

            if (evalCase.expectedTool() == null) {
                if (!response.toolsUsed().isEmpty()) {
                    failures.add(evalCase.description() + "：应拒绝但调用了 " + tools);
                    continue;
                }
                if (!response.answer().contains(evalCase.mustContain())) {
                    failures.add(evalCase.description() + "：拒绝时应说明能力边界，实际回答=" + brief(response.answer()));
                    continue;
                }
            } else {
                if (!response.toolsUsed().contains(evalCase.expectedTool())) {
                    failures.add(evalCase.description() + "：期望工具 " + evalCase.expectedTool()
                            + "，实际 " + tools + "，回答=" + brief(response.answer()));
                    continue;
                }
                if (!response.answer().contains(evalCase.mustContain())) {
                    failures.add(evalCase.description() + "：回答未包含「" + evalCase.mustContain()
                            + "」，实际=" + brief(response.answer()) + "，工具返回=" + observations(response));
                    continue;
                }
            }
            passed++;
        }

        // 前置条件自检：评测用的商品必须能被检索到，否则单品画像类用例的失败与被测逻辑无关
        assertTrue(productService.page(storeId, knownProductName, null, null, 1, 5).records().stream()
                        .anyMatch(item -> item.name().equals(knownProductName)),
                "评测前置条件不成立：商品「" + knownProductName + "」检索不到");
        AgentToolExecutor.ToolRun probe = toolExecutor.execute(admin, storeId, "product_profile",
                Map.of("keyword", knownProductName), 0);
        assertTrue(probe.outcome().success(),
                "评测前置条件不成立：单品画像工具查不到「" + knownProductName + "」，返回=" + brief(probe.outcome().summary()));

        assertEquals(evalCases().size(), passed,
                "规则兜底评测集未全绿（" + passed + "/" + evalCases().size() + "）：\n - " + String.join("\n - ", failures));
    }

    /** 把工具的真实返回拼出来，失败时能直接看到"它到底查到了什么"。 */
    private String observations(AgentDtos.ChatResponse response) {
        return response.steps().stream()
                .map(step -> step.tool() + " → " + brief(step.observation()))
                .reduce((a, b) -> a + " | " + b)
                .orElse("（没有调用任何工具）");
    }

    @Test
    void 规则路径永不自动下单_写操作只能人工确认() {
        // 各种"想让它直接下单"的说法：规则路径都不允许调用写工具（draft_purchase_order）
        List<String> writeAttempts = List.of(
                "帮我生成采购单草稿",
                "直接下单进货 10 件",
                "帮我把这些商品都补货入库",
                "自动把库存补上");

        for (String question : writeAttempts) {
            AgentDtos.ChatResponse response = agentRuntime.chat(admin, storeId,
                    new AgentDtos.ChatRequest(null, question));
            assertFalse(response.toolsUsed().contains("draft_purchase_order"),
                    "规则路径不得自动调用写工具（问法：" + question + "，回答：" + brief(response.answer()) + "）");
        }
    }

    @Test
    void 会话记忆与上下文隔离() {
        AgentDtos.ChatResponse first = agentRuntime.chat(admin, storeId,
                new AgentDtos.ChatRequest(null, "哪些商品快过期了"));
        assertNotNull(first.sessionId());

        // 带上 sessionId 追问：必须落在同一段对话里（同一份记忆）
        AgentDtos.ChatResponse second = agentRuntime.chat(admin, storeId,
                new AgentDtos.ChatRequest(first.sessionId(), "那要不要补货"));
        assertEquals(first.sessionId(), second.sessionId(), "带 sessionId 的追问应落在同一会话");
        assertTrue(second.answer().length() > 10);

        // 不带 sessionId = "接着该用户当前对话"：仍是同一段记忆，而不是凭空开一段新的
        AgentDtos.ChatResponse third = agentRuntime.chat(admin, storeId,
                new AgentDtos.ChatRequest(null, "今天卖了多少"));
        assertEquals(first.sessionId(), third.sessionId(), "不带 sessionId 应复用该用户当前会话");

        // 显式「新会话」：上下文必须真的清掉（光在前端丢掉 sessionId 是清不掉的）
        String fresh = agentRuntime.resetSession(admin.userId());
        assertFalse(first.sessionId().equals(fresh), "新会话应是新的 sessionId");
        AgentDtos.ChatResponse afterReset = agentRuntime.chat(admin, storeId,
                new AgentDtos.ChatRequest(null, "今天卖了多少"));
        assertEquals(fresh, afterReset.sessionId(), "新会话之后不带 sessionId 的提问应落在新会话上");
    }

    private String brief(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine;
    }

    private ProductDtos.View createProduct(String name, int initStock, String salePrice, String purchasePrice) {
        return productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                "EVAL-" + UUID.randomUUID().toString().substring(0, 8), "规格", "件",
                new BigDecimal(purchasePrice), new BigDecimal(salePrice), initStock, 10, 365));
    }
}
