package com.retailsuite.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailsuite.ai.dto.AiDtos;
import com.retailsuite.ai.llm.LlmClient;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 经营助手：用自然语言问经营数据。
 *
 * 安全边界（这决定了它为什么可以放心用）：
 * - 只挂**只读工具**（营业额、TOP 商品、库存预警、商品库存、对账），没有任何写操作；
 * - 所有查询强制带当前登录门店的 storeId，问不出别家数据；
 * - 模型只负责"选哪个工具、传什么参数"，真正的数据由 Java 查库返回，模型不接触也不编造数字；
 * - 没配模型时走本地规则意图识别，功能照样可用（CI 里跑的就是这条分支）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    private static final int MAX_LLM_STEPS = 3;

    private static final String SYSTEM_PROMPT = """
            你是门店经营助手。你可以调用以下只读工具（只能查询，不能修改任何数据）：
            - sales_summary: 参数 {"date":"YYYY-MM-DD"}，返回该日营业额/订单数/退款/毛利
            - top_products: 参数 {"from":"YYYY-MM-DD","to":"YYYY-MM-DD","limit":10}，返回畅销商品
            - low_stock: 无参数，返回库存预警商品
            - product_stock: 参数 {"keyword":"商品名或条码"}，返回该商品当前库存
            - reconcile: 参数 {"date":"YYYY-MM-DD"}，返回销售数量与库存出库的对账差异
            每轮只输出一个 JSON：
            {"action":"tool","tool":"工具名","args":{...},"thought":"为什么查它"}
            或数据足够时：{"action":"answer","answer":"用中文给出的结论（不要编造数字）"}
            """;

    private final ReportService reportService;
    private final InventoryService inventoryService;
    private final ProductMapper productMapper;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public AiDtos.AskResponse ask(Long storeId, String question) {
        if (llmClient.configured()) {
            Optional<AiDtos.AskResponse> viaLlm = askWithLlm(storeId, question);
            if (viaLlm.isPresent()) {
                return viaLlm.get();
            }
            log.info("模型助手不可用，回退本地规则意图识别");
        }
        return askWithRules(storeId, question);
    }

    // ------------------------------------------------------------------ 模型分支

    private Optional<AiDtos.AskResponse> askWithLlm(Long storeId, String question) {
        List<String> toolsUsed = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        String prompt = question;
        for (int round = 0; round < MAX_LLM_STEPS; round++) {
            Optional<String> raw = llmClient.chatJson(SYSTEM_PROMPT, prompt);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> action;
            try {
                action = objectMapper.readValue(extractJson(raw.get()),
                        new TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception e) {
                log.warn("模型输出不是合法 JSON，回退规则分支：{}", e.getMessage());
                return Optional.empty();
            }
            String kind = String.valueOf(action.getOrDefault("action", "answer"));
            if (!"tool".equals(kind)) {
                String answer = String.valueOf(action.getOrDefault("answer", ""));
                if (answer.isBlank()) {
                    return Optional.empty();
                }
                steps.add("模型直接作答（第 " + (round + 1) + " 轮）");
                return Optional.of(new AiDtos.AskResponse(answer, "LLM", toolsUsed, steps));
            }
            String tool = String.valueOf(action.getOrDefault("tool", ""));
            Map<String, Object> args = action.get("args") instanceof Map<?, ?> map
                    ? toStringMap(map) : Map.of();
            steps.add("调用工具 " + tool + " " + args);
            String observation;
            try {
                observation = executeTool(storeId, tool, args);
                toolsUsed.add(tool);
            } catch (RuntimeException e) {
                observation = "工具执行失败：" + e.getMessage();
            }
            prompt = question + "\n\n（上一轮工具 " + tool + " 的返回）\n" + observation
                    + "\n如数据已足够请直接给出 answer。";
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ 规则分支

    private AiDtos.AskResponse askWithRules(Long storeId, String question) {
        String text = question == null ? "" : question.trim();
        String date = resolveDate(text).toString();
        List<String> steps = new ArrayList<>();

        if (containsAny(text, "预警", "补货", "低库存", "快没货", "库存不足")) {
            steps.add("识别为「库存预警」意图");
            List<InventoryDtos.LowStockItem> items = inventoryService.lowStockItems(storeId);
            if (items.isEmpty()) {
                return new AiDtos.AskResponse("当前没有库存预警商品，各商品库存都在阈值之上。",
                        "RULE", List.of("low_stock"), steps);
            }
            StringBuilder sb = new StringBuilder("有 " + items.size() + " 个商品需要补货：");
            for (InventoryDtos.LowStockItem item : items) {
                sb.append("\n· ").append(item.name()).append("（剩 ").append(item.stock())
                        .append(" ").append(item.unit() == null ? "件" : item.unit())
                        .append("，阈值 ").append(item.lowStockThreshold()).append("）");
            }
            return new AiDtos.AskResponse(sb.toString(), "RULE", List.of("low_stock"), steps);
        }

        if (containsAny(text, "对账", "账实", "账对不对", "有差异")) {
            steps.add("识别为「对账」意图");
            ReportDtos.Reconcile reconcile = reportService.reconcile(storeId, LocalDate.parse(date));
            String answer = reconcile.consistent()
                    ? date + " 对账一致：核对 " + reconcile.checkedProducts() + " 个商品，销售数量与库存出库完全对得上。"
                    : date + " 对账发现 " + reconcile.diffs().size() + " 个商品存在差异：\n"
                    + reconcile.diffs().stream()
                    .map(row -> "· " + row.productName() + " 销售 " + row.soldQuantity()
                            + " 件，库存出库 " + row.flowQuantity() + " 件，差 " + row.diff() + " 件")
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            return new AiDtos.AskResponse(answer, "RULE", List.of("reconcile"), steps);
        }

        if (containsAny(text, "top", "TOP", "最畅销", "卖得最好", "销量最好", "畅销", "卖得好")) {
            steps.add("识别为「畅销商品」意图");
            LocalDate from = resolveRangeStart(text);
            List<ReportDtos.TopProduct> top = reportService.topProducts(storeId, from, LocalDate.now(), 5);
            if (top.isEmpty()) {
                return new AiDtos.AskResponse(from + " 至 " + LocalDate.now() + " 还没有销售数据。",
                        "RULE", List.of("top_products"), steps);
            }
            StringBuilder sb = new StringBuilder(from + " 至 " + LocalDate.now() + " 卖得最好的商品：");
            int index = 1;
            for (ReportDtos.TopProduct product : top) {
                sb.append("\n").append(index++).append(". ").append(product.productName())
                        .append("：").append(product.quantity()).append(" 件，")
                        .append(product.amount()).append(" 元，毛利 ").append(product.grossProfit()).append(" 元");
            }
            return new AiDtos.AskResponse(sb.toString(), "RULE", List.of("top_products"), steps);
        }

        // 单品库存：问句里出现"库存/还有多少"且带了商品名
        if (containsAny(text, "库存", "还剩", "还有多少")) {
            String keyword = stripKeywords(text, "库存", "还剩", "还有多少", "多少", "还有", "查询", "查一下", "查", "？", "?", "的", "呢");
            if (!keyword.isBlank()) {
                steps.add("识别为「单品库存」意图，关键字=" + keyword);
                List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .eq(Product::getStoreId, storeId)
                        .and(w -> w.like(Product::getName, keyword).or().eq(Product::getBarcode, keyword))
                        .last("LIMIT 5"));
                if (!products.isEmpty()) {
                    StringBuilder sb = new StringBuilder("找到 " + products.size() + " 个相关商品：");
                    for (Product product : products) {
                        sb.append("\n· ").append(product.getName()).append("：库存 ")
                                .append(product.getStock()).append(" ")
                                .append(product.getUnit() == null ? "件" : product.getUnit())
                                .append("，售价 ").append(product.getSalePrice())
                                .append(product.getStock() != null && product.getLowStockThreshold() != null
                                        && product.getStock() <= product.getLowStockThreshold() ? "（低于阈值，建议补货）" : "");
                    }
                    return new AiDtos.AskResponse(sb.toString(), "RULE", List.of("product_stock"), steps);
                }
            }
        }

        if (containsAny(text, "营业额", "销售额", "卖了多少", "收入", "毛利", "今天", "昨天", "本周", "本月", "客单价")) {
            steps.add("识别为「经营概览」意图，日期=" + date);
            ReportDtos.Overview overview = reportService.overview(storeId, LocalDate.parse(date));
            String answer = date + " 的经营情况：\n"
                    + "· 营业额：" + overview.netAmount() + " 元（销售额 " + overview.salesAmount()
                    + "，退款 " + overview.refundAmount() + "）\n"
                    + "· 订单：" + overview.orderCount() + " 笔，商品 " + overview.itemCount() + " 件\n"
                    + "· 毛利：" + overview.grossProfit() + " 元，客单价：" + overview.avgOrderAmount() + " 元";
            return new AiDtos.AskResponse(answer, "RULE", List.of("sales_summary"), steps);
        }

        steps.add("未命中任何意图，返回能力说明");
        return new AiDtos.AskResponse("我可以回答这几类问题：\n"
                + "· 今天/昨天的营业额、订单数、毛利（例：今天卖了多少）\n"
                + "· 畅销商品（例：本周卖得最好的商品）\n"
                + "· 库存预警（例：哪些商品需要补货）\n"
                + "· 单品库存（例：农夫山泉还有多少库存）\n"
                + "· 对账差异（例：今天对账有差异吗）", "RULE", List.of(), steps);
    }

    // ------------------------------------------------------------------ 工具执行（只读）

    private String executeTool(Long storeId, String tool, Map<String, Object> args) {
        return switch (tool) {
            case "sales_summary" -> {
                LocalDate date = parseDate(stringOf(args.get("date")));
                ReportDtos.Overview overview = reportService.overview(storeId, date);
                yield "日期=" + date + "，营业额=" + overview.netAmount() + "，订单数=" + overview.orderCount()
                        + "，商品件数=" + overview.itemCount() + "，退款=" + overview.refundAmount()
                        + "，毛利=" + overview.grossProfit();
            }
            case "top_products" -> {
                LocalDate from = parseDate(stringOf(args.get("from")));
                LocalDate to = parseDate(stringOf(args.get("to")));
                int limit = intOf(args.get("limit"), 10);
                List<ReportDtos.TopProduct> top = reportService.topProducts(storeId, from, to, limit);
                yield top.isEmpty() ? "该区间没有销售数据"
                        : top.stream().map(p -> p.productName() + "：" + p.quantity() + " 件，"
                                + p.amount() + " 元").reduce((a, b) -> a + "；" + b).orElse("");
            }
            case "low_stock" -> {
                List<InventoryDtos.LowStockItem> items = inventoryService.lowStockItems(storeId);
                yield items.isEmpty() ? "无库存预警"
                        : items.stream().map(i -> i.name() + "（剩 " + i.stock() + "，阈值 " + i.lowStockThreshold() + "）")
                                .reduce((a, b) -> a + "；" + b).orElse("");
            }
            case "product_stock" -> {
                String keyword = stringOf(args.get("keyword"));
                if (keyword == null) {
                    yield "缺少参数 keyword";
                }
                List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .eq(Product::getStoreId, storeId)
                        .and(w -> w.like(Product::getName, keyword).or().eq(Product::getBarcode, keyword))
                        .last("LIMIT 5"));
                yield products.isEmpty() ? "未找到商品：" + keyword
                        : products.stream().map(p -> p.getName() + "：库存 " + p.getStock()
                                + "，售价 " + p.getSalePrice()).reduce((a, b) -> a + "；" + b).orElse("");
            }
            case "reconcile" -> {
                LocalDate date = parseDate(stringOf(args.get("date")));
                ReportDtos.Reconcile reconcile = reportService.reconcile(storeId, date);
                yield reconcile.consistent() ? date + " 对账一致（核对 " + reconcile.checkedProducts() + " 个商品）"
                        : date + " 对账差异：" + reconcile.diffs().stream()
                                .map(row -> row.productName() + " 差 " + row.diff() + " 件")
                                .reduce((a, b) -> a + "；" + b).orElse("");
            }
            default -> "未知工具：" + tool;
        };
    }

    // ------------------------------------------------------------------ 小工具

    private LocalDate resolveDate(String text) {
        LocalDate today = LocalDate.now();
        if (text.contains("昨天") || text.contains("昨日")) {
            return today.minusDays(1);
        }
        if (text.contains("前天")) {
            return today.minusDays(2);
        }
        return today;
    }

    private LocalDate resolveRangeStart(String text) {
        LocalDate today = LocalDate.now();
        if (text.contains("今天") || text.contains("今日")) {
            return today;
        }
        if (text.contains("昨天") || text.contains("昨日")) {
            return today.minusDays(1);
        }
        if (text.contains("本月") || text.contains("这个月")) {
            return today.withDayOfMonth(1);
        }
        if (text.contains("本周") || text.contains("这周")) {
            return today.with(DayOfWeek.MONDAY);
        }
        return today.minusDays(6);
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            return LocalDate.now();
        }
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String stripKeywords(String text, String... keywords) {
        String result = text;
        for (String keyword : keywords) {
            result = result.replace(keyword, " ");
        }
        return result.trim();
    }

    private Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> map.put(String.valueOf(key), value));
        return map;
    }

    private String stringOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }
}
