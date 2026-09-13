package com.retailsuite.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 草稿（自然语言录入的中间态）。
 *
 * 这是"AI 不直接改账"的落点：模型/规则解析的结果先存成草稿（PENDING），
 * 人工在界面上看过、改过、点确认后才生成真实单据。raw_text 与 parsed_json 都留存，
 * 出问题时能回答"当时模型看到了什么、解析成什么、人改成了什么"。
 */
@Data
@TableName("ai_draft")
public class AiDraft {

    public static final String TYPE_PURCHASE = "PURCHASE";
    public static final String TYPE_SALE = "SALE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_DISCARDED = "DISCARDED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeId;

    private String draftType;

    private String rawText;

    private String parsedJson;

    private String status;

    /** 解析来源：LLM 或 RULE（本地规则），便于对比两者的准确率 */
    private String source;

    private Long createdBy;

    private Long confirmedBy;

    /** 确认后生成的业务单号 */
    private String createdRefNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
