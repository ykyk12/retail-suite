package com.retailsuite.sales.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售单（收银小票）。
 *
 * request_id 上有唯一索引：这是**幂等的真正落点**——收银员手抖点两下、扫码枪连发、
 * 网络重试，都会被数据库挡住，同一请求只会产生一笔订单（应用层内存 Map 做不到跨实例、也会丢）。
 *
 * 状态：PAID（已收款）→ PARTIAL_REFUNDED（部分退货）→ REFUNDED（整单退货）。
 */
@Data
@TableName("sale_order")
public class SaleOrder {

    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_PARTIAL_REFUNDED = "PARTIAL_REFUNDED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    public static final String PAY_CASH = "CASH";
    public static final String PAY_WECHAT = "WECHAT";
    public static final String PAY_ALIPAY = "ALIPAY";
    public static final String PAY_CARD = "CARD";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeId;

    private String orderNo;

    /** 客户端生成的请求幂等键（唯一索引） */
    private String requestId;

    private String customerName;

    private Integer itemCount;

    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    private BigDecimal refundAmount;

    private String payMethod;

    private String status;

    private String remark;

    private Long operatorId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
