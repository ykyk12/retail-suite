package com.retailsuite.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购单（进货单）。
 * 状态机：DRAFT（草稿，可改可删）→ CONFIRMED（已入库，库存已增加，不可改）→ 只能通过退货流程冲销。
 * 采购单本身不动库存，**确认时**才调用库存服务入库——这样"录单"和"入库"两件事可以分开，符合门店实际操作。
 */
@Data
@TableName("purchase_order")
public class PurchaseOrder {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CANCELED = "CANCELED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeId;

    private String orderNo;

    private String supplierName;

    private Integer itemCount;

    private BigDecimal totalAmount;

    private String status;

    private String remark;

    private Long operatorId;

    private LocalDateTime confirmedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
