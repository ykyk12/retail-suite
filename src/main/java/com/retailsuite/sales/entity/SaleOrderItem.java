package com.retailsuite.sales.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 销售单明细。refunded_quantity 支持部分退货，退货时用条件更新防超退。 */
@Data
@TableName("sale_order_item")
public class SaleOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long productId;

    /** 冗余当时的商品名与条码：商品改名/改条码后，历史小票不能跟着变 */
    private String productName;

    private String barcode;

    private Integer quantity;

    private Integer refundedQuantity;

    private BigDecimal unitPrice;

    private BigDecimal amount;

    private LocalDateTime createdAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
