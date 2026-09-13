package com.retailsuite.ai.nlp;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文进货语句解析（本地规则版，不依赖大模型）。
 *
 * 为什么必须有它：
 * - 门店网络差 / 没配 API Key / 模型超配额时，录入单据不能直接不可用；
 * - 规则解析是确定性的、可写单元测试；模型解析不可复现。
 *
 * 支持的说法（示例）：
 *   "进了 10 瓶农夫山泉 单价 1.2"
 *   "采购：5 箱可口可乐 每箱 3.5 元"
 *   "进货 东方树叶 20 瓶 3.5 元、乐事薯片 10 袋 4.2"
 *   "6921168509256 20 个 1.5"（条码识别）
 *
 * 解析原则：**宁可少解析、也不瞎猜**。解析不出的行原样回给用户修改，
 * 而不是猜一个商品出来——错单据比没单据更麻烦。
 */
@Component
public class ChineseOrderParser {

    /** 所有数字（含小数），位置用于区分"数量"和"价格" */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    /** 价格：单价/价格/进价/每X + 数字，或 "数字 + 元" */
    private static final Pattern PRICE = Pattern.compile(
            "(?:单价|价格|进价|每[瓶箱袋包件盒提罐个只把条]?|每)\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*(?:元|块钱)");
    /** 条码：连续 8~14 位数字 */
    private static final Pattern BARCODE = Pattern.compile("\\d{8,14}");
    /** 数量 + 单位（用于从商品名里剥离，例如 "7 瓶" / "50只"） */
    private static final Pattern QUANTITY_WITH_UNIT = Pattern.compile("\\d+(?:\\.\\d+)?\\s*[瓶箱袋包件盒提罐个只把条]?");
    private static final Pattern SEGMENT_SPLIT = Pattern.compile("[，,、；;。\\n]+");
    private static final Pattern NOISE = Pattern.compile(
            "进货|采购|进了|买入|买了|录入|登记|以及|还有|单价|价格|进价|块钱|元|请|帮我|一下|总共|合计|共|货号|条码");

    public record ParsedItem(String rawText,
                             String productKeyword,
                             String barcode,
                             Integer quantity,
                             BigDecimal price) {

        /** 只有"数量 + （名称 或 条码）"都解析出来才算可用。 */
        public boolean usable() {
            return quantity != null && ((productKeyword != null && !productKeyword.isBlank()) || barcode != null);
        }
    }

    public List<ParsedItem> parse(String text) {
        List<ParsedItem> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }
        for (String segment : SEGMENT_SPLIT.split(text.trim())) {
            String raw = segment.trim();
            if (!raw.isEmpty()) {
                items.add(parseSegment(raw));
            }
        }
        return items;
    }

    private ParsedItem parseSegment(String raw) {
        // 1) 条码：优先识别，并把它占用的数字从后续判断中排除
        String barcode = null;
        int barcodeStart = -1;
        int barcodeEnd = -1;
        Matcher barcodeMatcher = BARCODE.matcher(raw);
        if (barcodeMatcher.find()) {
            barcode = barcodeMatcher.group();
            barcodeStart = barcodeMatcher.start();
            barcodeEnd = barcodeMatcher.end();
        }

        // 2) 价格：显式价格标记，或"数字 + 元"
        BigDecimal price = null;
        int priceStart = -1;
        int priceEnd = -1;
        Matcher priceMatcher = PRICE.matcher(raw);
        if (priceMatcher.find()) {
            String value = priceMatcher.group(1) != null ? priceMatcher.group(1) : priceMatcher.group(2);
            price = new BigDecimal(value);
            priceStart = priceMatcher.start();
            priceEnd = priceMatcher.end();
        }

        // 3) 数量：剩下的第一个正整数（排除条码与价格占用的区间）
        Integer quantity = null;
        Matcher numberMatcher = NUMBER.matcher(raw);
        while (numberMatcher.find()) {
            int start = numberMatcher.start();
            int end = numberMatcher.end();
            if (overlaps(start, end, barcodeStart, barcodeEnd) || overlaps(start, end, priceStart, priceEnd)) {
                continue;
            }
            String value = numberMatcher.group();
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.stripTrailingZeros().scale() <= 0 && parsed.compareTo(BigDecimal.ZERO) > 0) {
                quantity = parsed.intValue();
                break;
            }
        }

        // 4) 商品名：把条码、价格、数量（含单位）与噪音词都抠掉，取剩下最长的一段
        //    注意顺序：先把"数字+单位"整体去掉，否则 "7 瓶AI测试椰汁" 会留下 "瓶" 导致名称匹配失败
        String residual = raw;
        if (barcode != null) {
            residual = residual.replace(barcode, " ");
        }
        residual = PRICE.matcher(residual).replaceAll(" ");
        residual = QUANTITY_WITH_UNIT.matcher(residual).replaceAll(" ");
        residual = NOISE.matcher(residual).replaceAll(" ");
        String keyword = longestToken(residual);

        return new ParsedItem(raw, keyword, barcode, quantity, price);
    }

    private boolean overlaps(int start, int end, int otherStart, int otherEnd) {
        if (otherStart < 0 || otherEnd < 0) {
            return false;
        }
        return start < otherEnd && otherStart < end;
    }

    private String longestToken(String text) {
        String best = "";
        for (String token : text.split("[\\s\\p{Punct}\\p{IsPunctuation}]+")) {
            String trimmed = token.trim();
            if (trimmed.length() > best.length()) {
                best = trimmed;
            }
        }
        return best.isEmpty() ? null : best;
    }
}
