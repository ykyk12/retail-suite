package com.retailsuite.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** id 生成：单号（可读、按时间排序）与短 traceId。 */
public final class Ids {

    private static final DateTimeFormatter ORDER_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Ids() {
    }

    public static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 业务单号：前缀 + 时间 + 4 位随机，便于人工核对与按时间排序。
     * 注意：并发下理论可能重复，所以单号列上仍建唯一索引，由数据库兜底（应用层不假装它是绝对唯一）。
     */
    public static String orderNo(String prefix) {
        String tail = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        return prefix + LocalDateTime.now().format(ORDER_FORMAT) + tail;
    }

    /** 批次号：B + 时间 + 4 位随机，可直接打印在货架标签上人工核对。 */
    public static String batchNo() {
        String tail = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        return "B" + LocalDateTime.now().format(ORDER_FORMAT) + tail;
    }
}
