package com.retailsuite.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商品批次（保质期管理的载体）。
 *
 * 为什么需要它：食品/生鲜/日化都有到期日，门店必须知道"哪一批什么时候过期、还剩多少"。
 * 只按商品聚合记库存会丢掉这些信息，临期预警与报损就无从谈起。
 *
 * 约定：
 * - expiry_date = production_date + shelf_life_days（由服务层计算并落库，便于按到期日排序与索引）
 * - 出库按 expiry_date 升序扣减（近效期先出），减少报损
 * - Σ批次数量的正确性由对账接口校验（必须等于 product.stock）
 */
@Data
@TableName("product_batch")
public class ProductBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeId;

    private Long productId;

    /** 批次号：门店内唯一，入库时生成（如 B20260913-xxxx），便于人工核对货架标签 */
    private String batchNo;

    private LocalDate productionDate;

    private LocalDate expiryDate;

    /** 该批次剩余数量 */
    private Integer quantity;

    /** 该批次成本价（同一商品不同批次的进价可能不同，毛利要按实际批次成本算） */
    private BigDecimal costPrice;

    private Long purchaseOrderId;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /** 是否已过期（相对给定日期）。 */
    public boolean expiredAt(LocalDate date) {
        return expiryDate != null && expiryDate.isBefore(date);
    }

    /** 距到期天数；无到期日返回 null。 */
    public Long daysToExpiry(LocalDate date) {
        return expiryDate == null ? null : java.time.temporal.ChronoUnit.DAYS.between(date, expiryDate);
    }
}
