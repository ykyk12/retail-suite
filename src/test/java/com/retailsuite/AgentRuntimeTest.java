package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.agent.dto.AgentDtos;
import com.retailsuite.agent.runtime.AgentRuntime;
import com.retailsuite.agent.runtime.AgentSessionStore;
import com.retailsuite.agent.runtime.AgentToolExecutor;
import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.AgentToolRegistry;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.purchase.entity.PurchaseOrder;
import com.retailsuite.purchase.mapper.PurchaseOrderMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管家 Agent 运行时：工具注册、权限过滤、规则兜底、会话记忆，以及最关键的**安全边界**——
 * 写操作只产出草稿，绝不直接改库存。
 *
 * 测试环境没有配模型，所以这里验的是规则路径（门店断网时的真实路径）；
 * 模型路径的协议解析在 AgentRuntime 里与规则路径共用同一个执行器，权限与审计行为一致。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRuntimeTest {

    @Autowired
    private AgentRuntime agentRuntime;
    @Autowired
    private AgentToolExecutor executor;
    @Autowired
    private AgentToolRegistry registry;
    @Autowired
    private AgentSessionStore sessionStore;
    @Autowired
    private ProductService productService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private PurchaseOrderMapper purchaseOrderMapper;
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
        admin = new AuthUser(9001L, storeId, "agent-admin", "测试店长", Set.of("ADMIN"),
                Set.of("report:read", "inventory:read", "inventory:loss", "product:read",
                        "purchase:read", "purchase:write", "ai:use"));
        cashier = new AuthUser(9002L, storeId, "agent-cashier", "测试收银员", Set.of("CASHIER"),
                Set.of("report:read", "inventory:read", "product:read", "ai:use"));
    }

    @Test
    void 工具注册表包含管家核心能力且区分只读与写操作() {
        List<AgentTool> tools = registry.all();

        assertTrue(tools.size() >= 8, "至少应注册 8 个业务工具，实际 " + tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("product_profile")), "应有单品画像工具");
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("expiry_alert")), "应有保质期工具");
        AgentTool draftTool = tools.stream().filter(t -> t.name().equals("draft_purchase_order"))
                .findFirst().orElseThrow();
        assertFalse(draftTool.readOnly(), "生成采购单是写操作");
        assertTrue(draftTool.requiresConfirmation(), "写操作必须标记需人工确认");
        assertEquals("purchase:write", draftTool.permission());
    }

    @Test
    void 权限过滤_收银员看不到也调不动进货工具() {
        assertTrue(registry.catalogFor(cashier).contains("product_profile"));
        assertFalse(registry.catalogFor(cashier).contains("draft_purchase_order"),
                "没有采购权限的账号，工具目录里不应出现写工具");
        assertTrue(registry.catalogFor(admin).contains("draft_purchase_order"));

        BizException error = assertThrows(BizException.class, () -> executor.execute(cashier, storeId,
                "draft_purchase_order", Map.of("items", List.of(Map.of("keyword", "任意", "quantity", 1))), 0));
        assertEquals(ErrorCode.FORBIDDEN, error.getCode(), "越权调用必须被拒绝，而不是靠提示词约束");
    }

    @Test
    void 规则路径能回答哪个商品最好卖() {
        AgentDtos.ChatResponse response = agentRuntime.chat(admin, storeId,
                new AgentDtos.ChatRequest(null, "哪个商品最好卖"));

        assertEquals("RULE", response.source(), "未配置模型时走规则兜底");
        assertTrue(response.toolsUsed().contains("sales_ranking"), "应使用销售排行工具：" + response.toolsUsed());
        assertTrue(response.answer().contains("排行"), response.answer());
        assertFalse(response.steps().isEmpty(), "要能说明它查了什么");
    }

    @Test
    void 规则路径的单品画像给出进价售价毛利率库存与到期日() {
        ProductDtos.View product = createPerishable("管家测试牛奶", 60, "12.00", "6.00");
        inventoryService.increase(storeId, product.id(), 12, "PURCHASE", "PO-AGENT", "到货",
                new InventoryService.BatchInbound(LocalDate.now().minusDays(2), 60, new BigDecimal("6.00"), null, null));

        AgentDtos.ChatResponse response = agentRuntime.chat(admin, storeId,
                new AgentDtos.ChatRequest(null, product.name() + " 的进价售价和库存"));

        assertTrue(response.toolsUsed().contains("product_profile"), response.toolsUsed().toString());
        String answer = response.answer();
        assertTrue(answer.contains("进价"), answer);
        assertTrue(answer.contains("售价"), answer);
        assertTrue(answer.contains("毛利率"), answer);
        assertTrue(answer.contains("当前库存"), answer);
        assertTrue(answer.contains("到期") && answer.contains("剩"), "必须给出到期日或剩余天数：" + answer);
        assertTrue(answer.contains(product.name()), answer);
    }

    @Test
    void 保质期管家能列出临期与过期批次() {
        ProductDtos.View product = createPerishable("管家测试酸奶", 20, "8.00", "4.00");
        inventoryService.increase(storeId, product.id(), 5, "PURCHASE", "PO-EXP", "临期批次",
                new InventoryService.BatchInbound(LocalDate.now().minusDays(15), 20, new BigDecimal("4.00"), null, null));

        AgentDtos.ChatResponse response = agentRuntime.chat(admin, storeId,
                new AgentDtos.ChatRequest(null, "有哪些临期要处理"));

        assertTrue(response.toolsUsed().contains("expiry_alert"), response.toolsUsed().toString());
        assertTrue(response.answer().contains("临期"), response.answer());
        assertTrue(response.answer().contains(product.name()), response.answer());
        assertTrue(response.answer().contains("剩"), "要告诉还剩几天：" + response.answer());
    }

    @Test
    void 写操作只生成采购单草稿且不改动库存() {
        ProductDtos.View product = createPerishable("管家测试饼干", 180, "10.00", "5.00");
        int stockBefore = product.stock();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("supplierName", "管家测试供应商");
        args.put("items", List.of(Map.of("productId", product.id(), "quantity", 24)));
        AgentToolExecutor.ToolRun run = executor.execute(admin, storeId, "draft_purchase_order", args, 0);

        assertTrue(run.outcome().success(), run.outcome().summary());
        assertTrue(run.step().requiresConfirmation(), "写操作轨迹必须标记需人工确认");
        String orderNo = String.valueOf(run.outcome().data().get("orderNo"));
        PurchaseOrder order = purchaseOrderMapper.selectOne(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getOrderNo, orderNo));
        assertNotNull(order);
        assertEquals(PurchaseOrder.STATUS_DRAFT, order.getStatus(), "必须是草稿状态");
        assertEquals(stockBefore, productMapper.selectById(product.id()).getStock(),
                "管家生成草稿不能直接改库存——库存只有在采购页确认入库后才增加");
        assertTrue(run.outcome().summary().contains("确认入库"), run.outcome().summary());
    }

    @Test
    void 答不了的问题会说清能力边界而不是编造() {
        AgentDtos.ChatResponse response = agentRuntime.chat(admin, storeId,
                new AgentDtos.ChatRequest(null, "帮我预测下个季度的天气如何"));

        assertTrue(response.toolsUsed().isEmpty(), "答不了就不该乱调工具：" + response.toolsUsed());
        assertTrue(response.answer().contains("我可以回答"), response.answer());
    }

    @Test
    void 缺少必填参数时明确拒绝而不是瞎猜() {
        AgentToolExecutor.ToolRun run = executor.execute(admin, storeId, "product_profile", Map.of(), 0);

        assertFalse(run.outcome().success());
        assertTrue(run.outcome().summary().contains("keyword"), run.outcome().summary());
    }

    @Test
    void 会话记忆保留多轮上下文且按用户隔离() {
        sessionStore.clear();
        agentRuntime.chat(admin, storeId, new AgentDtos.ChatRequest("S-TEST", "今天卖了多少"));
        AgentDtos.ChatResponse second = agentRuntime.chat(admin, storeId,
                new AgentDtos.ChatRequest("S-TEST", "有哪些临期要处理"));

        assertEquals("S-TEST", second.sessionId());
        Optional<AgentSessionStore.AgentSession> session = sessionStore.find(admin.userId(), "S-TEST");
        assertTrue(session.isPresent());
        assertEquals(2, session.get().turns().size(), "两轮对话都应记住");
        assertTrue(sessionStore.find(cashier.userId(), "S-TEST").isEmpty(), "不同用户的会话不能串");
    }

    private ProductDtos.View createPerishable(String name, Integer shelfLifeDays, String salePrice, String purchasePrice) {
        return productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                "BAR-" + System.nanoTime(), "规格", "件",
                new BigDecimal(purchasePrice), new BigDecimal(salePrice), 0, 3, shelfLifeDays));
    }
}
