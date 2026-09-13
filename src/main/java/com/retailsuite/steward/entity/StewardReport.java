package com.retailsuite.steward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 管家巡检日报（落库）。
 *
 * 为什么要持久化，而不是每次打开页面现算：
 * 1) 店长要能看到"昨天管家说了什么、我处理了没有"——这是管理动作，必须可回溯；
 * 2) 定时巡检发生在凌晨，白天翻看时数据可能已经变了，报告要保留当时的口径；
 * 3) 结构化发现（findings）落库后，前端才能做卡片、一键转草稿与已读标记。
 *
 * 幂等：唯一键 (store_id, report_date)，同一天重复巡检覆盖同一行，不会日均两三条。
 */
@Data
@TableName("steward_report")
public class StewardReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeId;

    private LocalDate reportDate;

    /** SCHEDULED（定时） / MANUAL（手动触发）——说明这份报告是谁要求生成的 */
    private String source;

    /** 一句话总结，用于列表页与消息提醒 */
    private String headline;

    private Integer findingCount;

    private Integer highCount;

    /** 结构化发现（JSON 数组，元素结构见 StewardDtos.Finding） */
    private String findings;

    private LocalDateTime generatedAt;

    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
