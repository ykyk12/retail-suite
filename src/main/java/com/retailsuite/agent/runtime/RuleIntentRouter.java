package com.retailsuite.agent.runtime;

import com.retailsuite.agent.dto.AgentDtos;
import com.retailsuite.security.AuthUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则意图路由：**没有配置大模型时的兜底**，也用于"模型不可用/输出不可解析"的降级。
 *
 * 为什么必须有它（门店真实场景，不是演示花活）：
 * 网络断了、模型超配额、Key 过期——收银台还得能查库存，店长还得能看临期。
 * 规则版覆盖最常见的问题（营业额、畅销、单品、临期、缺货、补货、对账、滞销），
 * 其余问题明确说清能力边界，绝不硬编一个答案。
 *
 * 与模型路径共用同一个 {@link AgentToolExecutor}，因此权限、限流、审计在两条路径上完全一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleIntentRouter {

    private final AgentToolExecutor executor;

    /**
     * 意图。
     *
     * @param guessed true 表示这条意图是"靠残留关键字猜的商品名"，猜错了不算用户的错——
     *                因此当工具执行失败时改写能力说明，而不是回一句"没找到这个商品"。
     */
    private record Intent(String tool, String header, Map<String, Object> args, boolean guessed) {
    }

    public AgentDtos.ChatResponse answer(AuthUser user, Long storeId, String question) {
        String text = question == null ? "" : question.trim();
        Intent intent = detect(text);
        List<AgentDtos.ToolCallStep> steps = new ArrayList<>();
        List<String> toolsUsed = new ArrayList<>();
        List<Map<String, Object>> cards = new ArrayList<>();

        AgentToolExecutor.ToolRun run;
        try {
            run = executor.execute(user, storeId, intent.tool(), new LinkedHashMap<>(intent.args()), 0);
        } catch (RuntimeException e) {
            // 权限不足/超限流等：说清原因（这也是一种"能力边界"）
            return capability(user, storeId, question, "这次没能帮你查：" + e.getMessage());
        }
        steps.add(run.step());
        toolsUsed.add(intent.tool());
        if (!run.outcome().data().isEmpty()) {
            cards.add(run.outcome().data());
        }
        if (!run.outcome().success() && intent.guessed()) {
            return capability(user, storeId, question, "没找到匹配的商品，换个说法（给商品名或条码）就行");
        }
        String answer = run.outcome().success()
                ? intent.header() + "\n" + run.outcome().summary()
                : "这次查询没成功：" + run.outcome().summary();
        return new AgentDtos.ChatResponse(null, answer, "RULE", toolsUsed, steps, cards);
    }

    public AgentDtos.ChatResponse capability(AuthUser user, Long storeId, String question) {
        return capability(user, storeId, question, null);
    }

    private AgentDtos.ChatResponse capability(AuthUser user, Long storeId, String question, String prefix) {
        String answer = (prefix == null ? "" : prefix + "\n\n") + """
                我可以帮你做这些事（每条都能给出具体数字与依据）：
                · 经营概况：今天/昨天/本周卖了多少、毛利多少、客单价多少
                · 哪个商品最好卖：按销售额/销量/毛利排行
                · 单品画像：进价、售价、毛利率、当前库存、批次与到期日、近 30 天销量、多久没卖出
                · 保质期管家：哪些批次临期（剩多少天、压了多少钱）、哪些已过期要下架报损
                · 缺货与补货：哪些有断货风险、建议补多少件，需要的话我直接生成采购单草稿
                · 滞销与利润：哪些卖不动、哪些毛利偏低
                · 对账：销售数量与库存出库是否一致
                说清"你要看什么 + 时间范围（可选）+ 商品名（可选）"就行。""";
        return new AgentDtos.ChatResponse(null, answer, "RULE", List.of(), List.of(), List.of());
    }

    /**
     * 意图识别（关键词匹配）。**顺序很重要**：先匹配更具体的意图。
     * 例如"临期"必须先于"库存"，否则"哪些临期商品还有多少"会被误判成普通库存查询。
     */
    private Intent detect(String text) {
        if (containsAny(text, "临期", "保质期", "到期", "过期", "快到期", "效期", "还能卖多久")) {
            return new Intent("expiry_alert", "保质期检查结果：", Map.of("days", 30), false);
        }
        if (containsAny(text, "对账", "账实", "账对不对", "有差异", "盘账")) {
            return new Intent("reconcile", "对账结果：", Map.of(), false);
        }
        if (containsAny(text, "补货", "该进多少", "进货建议", "缺多少", "需要进多少", "要不要进货")) {
            return new Intent("reorder_suggestion", "补货建议：", Map.of("days", 7), false);
        }
        if (containsAny(text, "缺货", "断货", "卖断", "库存健康", "够不够卖")) {
            return new Intent("stock_health", "库存健康检查：", Map.of(), false);
        }
        if (containsAny(text, "滞销", "卖不动", "没人买", "压货", "不动销")) {
            return new Intent("slow_movers", "滞销商品：", Map.of("days", 30), false);
        }
        if (containsAny(text, "毛利", "利润", "赚", "成本")) {
            String keyword = keywordOf(text);
            if (!keyword.isBlank()) {
                return new Intent("product_profile", "商品「" + keyword + "」的进货与利润情况：",
                        Map.of("keyword", keyword), true);
            }
            return new Intent("sales_ranking", "毛利排行：", Map.of("metric", "grossProfit", "days", 30), false);
        }
        if (containsAny(text, "最好卖", "畅销", "卖得最好", "销量最好", "top", "卖得好", "排名", "排行", "卖的最多")) {
            return new Intent("sales_ranking", "销售排行：", Map.of("metric", "amount", "days", 7), false);
        }
        if (containsAny(text, "单价", "进价", "售价", "库存", "还有多少", "还剩", "批次", "规格", "条码", "能卖多久")) {
            String keyword = keywordOf(text);
            if (!keyword.isBlank()) {
                return new Intent("product_profile", "商品「" + keyword + "」的情况：",
                        Map.of("keyword", keyword), true);
            }
            return new Intent("stock_health", "库存情况：", Map.of(), false);
        }
        if (containsAny(text, "营业额", "销售额", "卖了多少", "收入", "客单价", "今天", "昨天", "本周", "本月", "干了多少")) {
            return new Intent("sales_summary", "经营概况：", Map.of("period", periodOf(text)), false);
        }
        String keyword = keywordOf(text);
        if (!keyword.isBlank()) {
            // 只剩一个"可能是商品名"的词：试一试单品画像，查不到就回能力说明（见 guessed 的用途）
            return new Intent("product_profile", "商品「" + keyword + "」的情况：", Map.of("keyword", keyword), true);
        }
        return new Intent("__unknown__", "", Map.of(), true);
    }

    /** 是否完全识别不了（用于回能力说明）。 */
    public boolean unknownIntent(String question) {
        return "__unknown__".equals(detect(question == null ? "" : question.trim()).tool());
    }

    private boolean containsAny(String text, String... keywords) {
        String lower = text.toLowerCase();
        for (String keyword : keywords) {
            if (text.contains(keyword) || lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 从问句里抽出可能的商品名：去掉疑问词、时间词与功能词，取最长的一段。 */
    private String keywordOf(String text) {
        String cleaned = text;
        for (String noise : List.of("帮我", "请问", "查一下", "查询", "看一下", "看看", "是什么", "怎么样", "如何",
                "多少", "还有", "还剩", "库存", "进价", "售价", "单价", "毛利", "利润", "成本", "批次", "保质期",
                "到期", "临期", "过期", "规格", "条码", "今天", "昨天", "本周", "这周", "本月", "这个月", "最近",
                "近", "天", "的", "吗", "呢", "啊", "？", "?", "，", ",", "。", "、", "：", ":")) {
            cleaned = cleaned.replace(noise, " ");
        }
        String best = "";
        for (String token : cleaned.split("[\\s\\p{Punct}\\p{IsPunctuation}]+")) {
            if (token.length() > best.length()) {
                best = token;
            }
        }
        return best.trim();
    }

    private String periodOf(String text) {
        if (text.contains("昨天") || text.contains("昨日")) {
            return "YESTERDAY";
        }
        if (text.contains("本周") || text.contains("这周")) {
            return "THIS_WEEK";
        }
        if (text.contains("本月") || text.contains("这个月")) {
            return "THIS_MONTH";
        }
        if (text.contains("最近") || text.contains("近7") || text.contains("近 7")) {
            return "LAST_7_DAYS";
        }
        return "TODAY";
    }
}
