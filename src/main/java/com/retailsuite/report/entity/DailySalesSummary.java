package com.retailsuite.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日销售汇总（物化表）。
 *
 * 为什么要它：收银明细表会一直增长，报表如果每次都全表聚合，数据量大后会明显变慢。
 * 这里按"门店 + 日期"预聚合，报表直接查它；(store_id, summary_date) 上有唯一索引，重复汇总不会产生两行。
 * 代价是数据有延迟（默认每天 00:10 汇总昨天），所以接口同时提供"实时"和"汇总"两种口径。
 */
@Data
@TableName("daily_sales_summary")
public class DailySalesSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeId;

    private LocalDate summaryDate;

    private Integer orderCount;

    private Integer itemCount;

    private BigDecimal salesAmount;

    private BigDecimal refundAmount;

    private BigDecimal netAmount;

    private BigDecimal grossProfit;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
