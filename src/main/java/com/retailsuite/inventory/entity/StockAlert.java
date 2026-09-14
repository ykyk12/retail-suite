package com.retailsuite.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 低库存预警工单（闭环）。
 *
 * 与只读的"低库存列表"区别：列表只告诉你"现在有哪些货少了"，
 * 工单把它变成一条**可跟踪、可闭环**的任务——
 *   收银/报损把库存砸到阈值以下时自动开一条 OPEN 工单，
 *   同一商品只要还挂着 OPEN 工单就不重复开（避免每次收银都刷屏），
 *   店长补货/盘点后手动 RESOLVE，闭环才算完成。
 *
 * 唯一约束：(store_id, product_id, status) 不适合做唯一索引（status 是取值而非唯一键），
 * 所以"同商品只开一条 OPEN 单"由服务层在事务内"先查后插"保证——
 * 配合"出库扣减已持商品行排他锁"，同一商品的两次出库被串行化，不会并发开出两条。
 */
@Data
@TableName("stock_alert")
public class StockAlert {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeId;

    private Long productId;

    /** 冗余商品名，列表展示免 join */
    private String productName;

    /** 触发预警时的库存快照 */
    private Integer stockAtAlert;

    /** 触发时的阈值 */
    private Integer threshold;

    private String status;

    /** 闭环备注（补货数量/到货日期等） */
    private String resolveRemark;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
