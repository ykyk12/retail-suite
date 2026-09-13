package com.retailsuite.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品（含库存）。
 *
 * 库存刻意放在商品表里而不是单独的库存表：单店场景下"一商品一库存"，拆表只会多一次 join；
 * 但**库存变动必须走 InventoryService**（条件更新 + 流水），禁止业务代码直接 setStock，
 * 否则"库存从哪来、为什么会少"就查不清了。
 *
 * version 字段用于乐观锁（MyBatis-Plus @Version），适合"读-改-写"式更新；
 * 收银扣减走的是更优的"条件更新"（update ... where stock >= n），两者场景不同，见 InventoryService 注释。
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeId;

    private Long categoryId;

    private String name;

    /** 条码：收银台扫码检索用，门店内唯一（数据库唯一索引兜底） */
    private String barcode;

    private String spec;

    private String unit;

    /** 进价（成本），用于毛利计算 */
    private BigDecimal purchasePrice;

    /** 售价 */
    private BigDecimal salePrice;

    private Integer stock;

    /** 低于该值进入库存预警 */
    private Integer lowStockThreshold;

    /** 保质期天数：NULL 表示不追踪保质期（日用品等）；有值时入库需登记生产日期以推算到期日 */
    private Integer shelfLifeDays;

    /** 1 在售 / 0 停售 */
    private Integer status;

    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
