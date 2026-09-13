package com.retailsuite.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * id 生成：单号（可读、按时间排序）与短 traceId。
 *
 * 关于唯一性：单号 = 前缀 + 时间 + 8 位随机后缀。
 * 后缀必须是 8 位而不是 4 位——4 位时同一秒内只有 65536 种组合，
 * 门店连续确认多张采购单（或批量导入）就会撞唯一索引，整笔确认直接失败。
 * 这一点是真实踩到的：CI 全量跑测试时批次号在同一秒内重复，唯一索引报错。
 * 即便如此，数据库唯一索引仍保留为最终兜底，应用层不假装它是绝对唯一。
 */
public final class Ids {

    private static final DateTimeFormatter ORDER_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 后缀长度（36 进制字符数）：8 位 ≈ 2.8 万亿种，配合秒级时间足够用 */
    private static final int SUFFIX_LENGTH = 8;
    private static final char[] BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private Ids() {
    }

    public static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 业务单号：前缀 + 时间 + 8 位随机，便于人工核对与按时间排序。
     */
    public static String orderNo(String prefix) {
        return prefix + LocalDateTime.now().format(ORDER_FORMAT) + randomSuffix();
    }

    /** 批次号：B + 时间 + 8 位随机，可直接打印在货架标签上人工核对。 */
    public static String batchNo() {
        return "B" + LocalDateTime.now().format(ORDER_FORMAT) + randomSuffix();
    }

    /** 36 进制随机后缀（大写，避免大小写混排造成人工核对时的误读）。 */
    private static String randomSuffix() {
        StringBuilder sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(BASE36[RANDOM.nextInt(BASE36.length)]);
        }
        return sb.toString();
    }
}
