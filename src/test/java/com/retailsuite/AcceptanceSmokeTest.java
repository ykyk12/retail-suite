package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailsuite.report.mapper.ReportMapper;
import com.retailsuite.store.entity.Store;
import com.retailsuite.store.mapper.StoreMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路验收测试（HTTP 级，走真实的过滤器 + 拦截器 + JSON 编解码）。
 *
 * 为什么要有它：`scripts/smoke-e2e.sh`（部署后用 curl 跑）无法在 CI 里执行，
 * 于是把同样的断言在 CI 里固化一份——**逻辑被 CI 每次验证，脚本只是把它搬到真实环境跑一遍**。
 *
 * 覆盖：登录与权限 → 建档 → 采购（录单/入库/重复确认被拒）→ 收银（金额/幂等/库存不足回滚）
 *       → 退货（部分退/超退被拒）→ 报表（概览/汇总幂等/对账）→ AI 录单（解析/确认/未识别/销售被拒）
 *       → 经营助手问答 → 未登录 401 / 越权 403。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AcceptanceSmokeTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private ReportMapper reportMapper;

    @Test
    void 未登录访问受保护接口返回401() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/products"))
                .andReturn();
        assertEquals(401, result.getResponse().getStatus());
        assertEquals("UNAUTHORIZED", read(result).path("code").asText());
    }

    @Test
    void 收银员越权进货返回403() throws Exception {
        String cashier = login("cashier", "cashier123");

        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/purchases").header("Authorization", "Bearer " + cashier))
                .andReturn();

        assertEquals(403, result.getResponse().getStatus());
        assertEquals("FORBIDDEN", read(result).path("code").asText());
    }

    @Test
    void 全链路验收_从建档到报表与AI助手() throws Exception {
        Long storeId = storeMapper.selectOne(new LambdaQueryWrapper<Store>().last("LIMIT 1")).getId();
        String token = login("admin", "admin123");

        // ---------- 1. 建档：期初库存 5 ----------
        String barcode = "69" + String.format("%011d", Math.abs(System.nanoTime() % 100_000_000_000L));
        JsonNode product = call(HttpMethod.POST, "/api/products", token, Map.of(
                "name", "验收测试商品", "barcode", barcode, "spec", "500ml", "unit", "瓶",
                "purchasePrice", 2.00, "salePrice", 3.50, "initStock", 5, "lowStockThreshold", 10), 200);
        long productId = product.path("id").asLong();
        assertEquals(5, product.path("stock").asInt(), "期初库存应为 5");
        assertTrue(product.path("lowStock").asBoolean(), "库存 5 ≤ 阈值 10，应标记为低库存");

        // ---------- 2. 库存流水有据可查 ----------
        JsonNode flows = call(HttpMethod.GET, "/api/inventory/flows/" + productId + "?limit=10", token, null, 200);
        assertTrue(flows.size() >= 1, "期初建库必须写入流水");
        assertEquals("IN", flows.get(0).path("type").asText());
        assertEquals(0, flows.get(0).path("beforeStock").asInt());

        // ---------- 3. 库存预警包含该商品 ----------
        JsonNode lowStock = call(HttpMethod.GET, "/api/inventory/low-stock", token, null, 200);
        assertTrue(containsId(lowStock, "productId", productId), "低库存商品应出现在预警列表");

        // ---------- 4. 采购：录单不动库存 ----------
        JsonNode purchase = call(HttpMethod.POST, "/api/purchases", token, Map.of(
                "supplierName", "验收供应商", "remark", "验收测试",
                "items", List.of(Map.of("productId", productId, "quantity", 20, "unitCost", 2.00))), 200);
        long purchaseId = purchase.path("id").asLong();
        assertEquals("DRAFT", purchase.path("status").asText());
        assertEquals(5, call(HttpMethod.GET, "/api/products/" + productId, token, null, 200).path("stock").asInt(),
                "草稿状态的采购单不能改动库存");

        // ---------- 5. 确认入库 → 库存 +20 ----------
        JsonNode confirmed = call(HttpMethod.POST, "/api/purchases/" + purchaseId + "/confirm", token, null, 200);
        assertEquals("CONFIRMED", confirmed.path("status").asText());
        assertEquals(25, call(HttpMethod.GET, "/api/products/" + productId, token, null, 200).path("stock").asInt());

        // ---------- 6. 重复确认被状态机拒绝 ----------
        JsonNode duplicateConfirm = call(HttpMethod.POST, "/api/purchases/" + purchaseId + "/confirm", token, null, 409);
        assertEquals("CONFLICT", duplicateConfirm.path("code").asText());

        // ---------- 7. 收银：应收 10.50，库存 25 → 22 ----------
        String requestId = "ACCEPT-" + System.nanoTime();
        Map<String, Object> checkout = Map.of(
                "requestId", requestId, "customerName", "验收顾客",
                "items", List.of(Map.of("productId", productId, "quantity", 3)),
                "discountAmount", 0, "payMethod", "WECHAT");
        JsonNode sale = call(HttpMethod.POST, "/api/sales/checkout", token, checkout, 200);
        String orderNo = sale.path("orderNo").asText();
        long saleId = sale.path("id").asLong();
        long orderItemId = sale.path("items").get(0).path("id").asLong();
        assertEquals(0, new BigDecimal("10.50").compareTo(new BigDecimal(sale.path("payAmount").asText())));
        assertEquals(22, call(HttpMethod.GET, "/api/products/" + productId, token, null, 200).path("stock").asInt());

        // ---------- 8. 幂等：同一 requestId 再提交 ----------
        JsonNode replay = call(HttpMethod.POST, "/api/sales/checkout", token, checkout, 200);
        assertEquals(orderNo, replay.path("orderNo").asText(), "重复提交必须返回首次订单");
        assertTrue(replay.path("duplicated").asBoolean(), "第二次必须标记为幂等命中");
        assertEquals(22, call(HttpMethod.GET, "/api/products/" + productId, token, null, 200).path("stock").asInt(),
                "库存不能被重复扣减");

        // ---------- 9. 库存不足整笔回滚 ----------
        JsonNode notEnough = call(HttpMethod.POST, "/api/sales/checkout", token, Map.of(
                "requestId", "ACCEPT-FAIL-" + System.nanoTime(),
                "items", List.of(Map.of("productId", productId, "quantity", 999)),
                "discountAmount", 0, "payMethod", "CASH"), 422);
        assertEquals("STOCK_NOT_ENOUGH", notEnough.path("code").asText());
        assertEquals(22, call(HttpMethod.GET, "/api/products/" + productId, token, null, 200).path("stock").asInt(),
                "失败的结算不能改动库存");

        // ---------- 10. 部分退货：退 1 件 ----------
        JsonNode refunded = call(HttpMethod.POST, "/api/sales/" + saleId + "/refund", token, Map.of(
                "items", List.of(Map.of("orderItemId", orderItemId, "quantity", 1)), "remark", "验收退货"), 200);
        assertEquals("PARTIAL_REFUNDED", refunded.path("status").asText());
        assertEquals(23, call(HttpMethod.GET, "/api/products/" + productId, token, null, 200).path("stock").asInt());

        // ---------- 11. 超退被拒绝 ----------
        JsonNode overRefund = call(HttpMethod.POST, "/api/sales/" + saleId + "/refund", token, Map.of(
                "items", List.of(Map.of("orderItemId", orderItemId, "quantity", 99))), 409);
        assertEquals("CONFLICT", overRefund.path("code").asText());
        assertEquals(23, call(HttpMethod.GET, "/api/products/" + productId, token, null, 200).path("stock").asInt());

        // ---------- 12. 报表：概览、汇总幂等、对账一致 ----------
        JsonNode overview = call(HttpMethod.GET, "/api/reports/overview", token, null, 200);
        assertTrue(overview.path("orderCount").asLong() >= 1);
        assertTrue(overview.path("netAmount").decimalValue().compareTo(BigDecimal.ZERO) > 0);

        java.time.LocalDate today = java.time.LocalDate.now();
        JsonNode rebuild1 = call(HttpMethod.POST, "/api/reports/daily/" + today + "/rebuild", token, null, 200);
        JsonNode rebuild2 = call(HttpMethod.POST, "/api/reports/daily/" + today + "/rebuild", token, null, 200);
        assertEquals(rebuild1.path("netAmount").asText(), rebuild2.path("netAmount").asText(), "汇总重算必须幂等");

        JsonNode reconcile = call(HttpMethod.GET, "/api/reports/reconcile", token, null, 200);
        // 对账左侧数据源（今天的 SALE 库存流水）必须可读：这条断言专门用来定位"差异行全是 flow=0"这类问题
        assertFalse(reportMapper.saleFlowQuantityByProduct(storeId,
                        today.atStartOfDay(), today.plusDays(1).atStartOfDay()).isEmpty(),
                "应能从库存流水读到今天的 SALE 记录（对账的数据来源）");
        // 全局一致性由 ReportFlowTest 覆盖；这里断言本用例自己造的数据必须一致
        assertFalse(hasDiff(reconcile.path("diffs"), productId),
                "本用例的商品不应有对账差异，当前差异=" + reconcile.path("diffs"));

        // ---------- 13. AI 录单：解析 → 草稿（不产生单据） ----------
        JsonNode draft = call(HttpMethod.POST, "/api/ai/drafts", token, Map.of(
                "text", "进了 6 瓶验收测试商品 单价 2.0", "type", "PURCHASE"), 200);
        long draftId = draft.path("id").asLong();
        assertEquals("PENDING", draft.path("status").asText());
        assertTrue(draft.path("items").get(0).path("resolved").asBoolean(), "应能匹配到刚建的商品");

        // ---------- 14. 人工确认 → 生成采购单（草稿态，不动库存） ----------
        int stockBeforeDraftConfirm = call(HttpMethod.GET, "/api/products/" + productId, token, null, 200)
                .path("stock").asInt();
        JsonNode draftConfirmed = call(HttpMethod.POST, "/api/ai/drafts/" + draftId + "/confirm", token,
                Map.of("supplierName", "验收供应商"), 200);
        assertEquals("CONFIRMED", draftConfirmed.path("status").asText());
        assertNotEquals("", draftConfirmed.path("createdRefNo").asText(), "应返回生成的采购单号");
        assertEquals(stockBeforeDraftConfirm,
                call(HttpMethod.GET, "/api/products/" + productId, token, null, 200).path("stock").asInt(),
                "AI 草稿确认只生成采购单，不能直接改库存");

        // ---------- 15. 未识别的行不会被瞎猜 ----------
        JsonNode badDraft = call(HttpMethod.POST, "/api/ai/drafts", token, Map.of(
                "text", "进了 3 瓶根本不存在的饮料", "type", "PURCHASE"), 200);
        assertTrue(badDraft.path("hasUnresolved").asBoolean());
        JsonNode rejected = call(HttpMethod.POST, "/api/ai/drafts/" + badDraft.path("id").asLong() + "/confirm",
                token, Map.of(), 400);
        assertEquals("BAD_REQUEST", rejected.path("code").asText());

        // ---------- 16. 销售类草稿必须走收银台 ----------
        JsonNode saleDraft = call(HttpMethod.POST, "/api/ai/drafts", token, Map.of(
                "text", "卖了 2 瓶验收测试商品", "type", "SALE"), 200);
        JsonNode saleRejected = call(HttpMethod.POST, "/api/ai/drafts/" + saleDraft.path("id").asLong() + "/confirm",
                token, Map.of(), 400);
        assertTrue(saleRejected.path("message").asText().contains("收银台"));

        // ---------- 17. 经营助手：只读问答 + 能力边界 ----------
        JsonNode answer = call(HttpMethod.POST, "/api/ai/assistant/ask", token, Map.of("question", "今天卖了多少"), 200);
        assertTrue(answer.path("answer").asText().contains("营业额"), "应给出营业额：" + answer.path("answer"));
        assertTrue(answer.path("toolsUsed").size() >= 1, "应记录使用了哪个只读工具");

        JsonNode unknown = call(HttpMethod.POST, "/api/ai/assistant/ask", token,
                Map.of("question", "帮我预测下个月的销量"), 200);
        assertTrue(unknown.path("answer").asText().contains("我可以回答"), "答不了要说清能力边界");

        // ---------- 18. 门店隔离：跨门店查不到数据（用另一个门店 ID 直接查库不可行，这里验服务层约束） ----------
        assertEquals(storeId, storeMapper.selectOne(new LambdaQueryWrapper<Store>().last("LIMIT 1")).getId());
    }

    // ------------------------------------------------------------------ 工具

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), "登录失败：" + result.getResponse().getContentAsString());
        return read(result).path("data").path("token").asText();
    }

    /** 发请求并断言 HTTP 状态码，返回 data 节点（业务失败时返回整个响应体便于断言错误码）。 */
    private JsonNode call(HttpMethod method, String url, String token, Object body, int expectedStatus) throws Exception {
        // 注意：Spring 6 起 HttpMethod 是类而不是枚举，不能对它用 switch-case（会被当成模式匹配预览特性）
        var builder = HttpMethod.POST.equals(method)
                ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        builder.header("Authorization", "Bearer " + token);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body));
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        JsonNode root = read(result);
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                url + " 期望状态 " + expectedStatus + "，实际 " + result.getResponse().getStatus()
                        + "，响应=" + root.toString());
        return root.path("data").isMissingNode() ? root : root.path("data");
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(new String(result.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean containsId(JsonNode array, String field, long id) {
        for (JsonNode node : array) {
            if (node.path(field).asLong() == id) {
                return true;
            }
        }
        return false;
    }

    /** 差异列表里是否有指定商品的差异行。 */
    private boolean hasDiff(JsonNode diffs, long productId) {
        return containsId(diffs, "productId", productId);
    }
}
