package com.retailsuite.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存流水（每一次库存变动的账）。
 * 有它才能回答"这个商品为什么只剩 3 个"——这是收银/进销存系统里最容易被忽略、也最容易被追问的一点。
 */
@Data
@TableName("inventory_flow")
public class InventoryFlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeId;

    private Long productId;

    /** IN 入库 / OUT 出库 / ADJUST 调整 */
    private String type;

    /** 变动数量（正数入库，负数出库） */
    private Integer quantity;

    private Integer beforeStock;

    private Integer afterStock;

    /** 来源类型：PURCHASE 采购 / SALE 销售 / REFUND 退货 / MANUAL 手工 */
    private String refType;

    /** 来源单号（可回溯到具体单据） */
    private String refNo;

    private String remark;

    private Long operatorId;

    private LocalDateTime createdAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
