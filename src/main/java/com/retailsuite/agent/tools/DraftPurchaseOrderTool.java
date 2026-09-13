package com.retailsuite.agent.tools;

import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.purchase.dto.PurchaseDtos;
import com.retailsuite.purchase.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【写操作】生成采购单**草稿**——管家"能干活"的落点，也是安全边界最好的例子：
 *
 * 工具只生成 DRAFT 状态的采购单，**不增加库存**；库存只有在人到「采购进货」页面点"确认入库"之后才变。
 * 也就是说：Agent 可以替你把单据准备好，但绝不经手账实数据。这条边界写在代码里，不靠"提示词叮嘱"。
 */
@Component
@RequiredArgsConstructor
public class DraftPurchaseOrderTool implements AgentTool {

    private final PurchaseService purchaseService;
    private final ProductService productService;

    @Override
    public String name() {
        return "draft_purchase_order";
    }

    @Override
    public String description() {
        return "生成采购单草稿（写操作，不会增加库存；需人工在采购页确认入库）。参数 items 必填：商品列表，每项含 productId 或 keyword、quantity、unitCost（可选）";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("items", "必填：数组，每项 {productId 或 keyword, quantity, unitCost 可选}");
        params.put("supplierName", "供应商名称，可选");
        params.put("remark", "备注，可选");
        return params;
    }

    @Override
    public String permission() {
        return "purchase:write";
    }

    @Override
    public boolean readOnly() {
        // 写操作：运行时会把这条轨迹标成"需人工确认"
        return false;
    }

    @Override
    public ToolOutcome execute(Long storeId, Map<String, Object> args) {
        List<Map<String, Object>> items = normalizeItems(args.get("items"));
        if (items.isEmpty()) {
            return ToolOutcome.fail("缺少必填参数 items：请给出要采购的商品与数量");
        }
        List<PurchaseDtos.ItemRequest> requests = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            Long productId = longValue(item.get("productId"));
            String keyword = item.get("keyword") == null ? null : String.valueOf(item.get("keyword")).trim();
            int quantity = intValue(item.get("quantity"), 0);
            if (quantity <= 0) {
                return ToolOutcome.fail("商品 " + (productId != null ? productId : keyword) + " 的 quantity 必须大于 0");
            }
            if (productId == null) {
                if (keyword == null || keyword.isBlank()) {
                    return ToolOutcome.fail("每一项必须给出 productId 或 keyword");
                }
                var page = productService.page(storeId, keyword, null, null, 1, 3);
                if (page.records().isEmpty()) {
                    return ToolOutcome.fail("没有找到商品「" + keyword + "」，请先确认商品名或先建档");
                }
                if (page.records().size() > 1) {
                    return ToolOutcome.fail("商品「" + keyword + "」匹配到多个结果，请改用 productId 指定");
                }
                productId = page.records().get(0).id();
            }
            BigDecimal unitCost = decimalValue(item.get("unitCost"));
            if (unitCost == null) {
                ProductDtos.View product = productService.detail(storeId, productId);
                unitCost = product.purchasePrice() == null ? BigDecimal.ZERO : product.purchasePrice();
            }
            requests.add(new PurchaseDtos.ItemRequest(productId, quantity, unitCost));
        }

        PurchaseDtos.View order;
        try {
            order = purchaseService.create(storeId, new PurchaseDtos.CreateRequest(
                    stringValue(args.get("supplierName")),
                    stringValue(args.get("remark")) == null ? "由管家 Agent 生成草稿" : stringValue(args.get("remark")),
                    requests));
        } catch (BizException e) {
            return ToolOutcome.fail("生成采购单草稿失败：" + e.getMessage());
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "生成采购单草稿异常：" + e.getMessage(), e);
        }

        String summary = "已生成采购单草稿 " + order.orderNo() + "（" + order.itemCount() + " 行，金额 "
                + order.totalAmount() + " 元，状态：" + order.statusText() + "）。"
                + "请到「采购进货」页面核对并点「确认入库」——确认后库存才会增加，管家不会直接改库存。";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", order.orderNo());
        data.put("status", order.status());
        data.put("statusText", order.statusText());
        data.put("itemCount", order.itemCount());
        data.put("totalAmount", order.totalAmount());
        data.put("requiresManualConfirm", true);
        return ToolOutcome.ok(summary, data);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeItems(Object raw) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    map.forEach((key, value) -> item.put(String.valueOf(key), value));
                    items.add(item);
                }
            }
        } else if (raw instanceof Map<?, ?> map) {
            Map<String, Object> item = new LinkedHashMap<>();
            map.forEach((key, value) -> item.put(String.valueOf(key), value));
            items.add(item);
        }
        return items;
    }

    private Long longValue(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return raw == null ? null : Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int intValue(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private BigDecimal decimalValue(Object raw) {
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return raw == null ? null : new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringValue(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
}
