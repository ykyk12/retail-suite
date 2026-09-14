package com.retailsuite.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 库存相关 DTO。 */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    /** 库存调整（盘点）：delta 为正表示盘盈、为负表示盘亏；理由必填，方便日后追责。 */
    public record AdjustRequest(
            @NotNull(message = "商品不能为空")
            Long productId,
            @NotNull(message = "调整数量不能为空")
            Integer delta,
            @Size(max = 200, message = "备注过长")
            String remark) {
    }

    /** 报损出库（过期/破损下架）：按近效期先出扣批次，必须填原因。 */
    public record LossRequest(
            @NotNull(message = "商品不能为空")
            Long productId,
            @NotNull(message = "报损数量不能为空")
            @Min(value = 1, message = "报损数量必须大于 0")
            Integer quantity,
            @Size(max = 64, message = "批次号过长")
            String batchNo,
            @Size(max = 200, message = "原因过长")
            String remark) {
    }

    public record FlowView(Long id,
                           Long productId,
                           String productName,
                           String type,
                           Integer quantity,
                           Integer beforeStock,
                           Integer afterStock,
                           String refType,
                           String refNo,
                           String remark,
                           /** 关联批次（跨批次出库时记首个批次，完整消耗见批次页） */
                           Long batchId,
                           LocalDateTime createdAt) {
    }

    public record LowStockItem(Long productId,
                               String name,
                               String barcode,
                               Integer stock,
                               Integer lowStockThreshold,
                               String unit) {
    }

    /** 批次视图（含剩余天数与是否已过期，前端可直接高亮）。 */
    public record BatchView(Long id,
                            String batchNo,
                            Long productId,
                            String productName,
                            LocalDate productionDate,
                            LocalDate expiryDate,
                            Integer quantity,
                            BigDecimal costPrice,
                            String remark,
                            Long daysToExpiry,
                            boolean expired) {
    }

    /** 临期/过期汇总（金额按批次成本价计算，代表"压在这批货上的钱"）。 */
    public record ExpirySummary(int alertDays,
                                long expiringBatchCount,
                                long expiringQuantity,
                                BigDecimal expiringAmount,
                                long expiredBatchCount,
                                long expiredQuantity,
                                BigDecimal expiredAmount) {
    }

    public record FlowPage(List<FlowView> records, long total, long page, long size) {
    }

    /** 低库存预警工单视图（OPEN=待处理，RESOLVED=已闭环）。 */
    public record AlertView(Long id,
                            Long productId,
                            String productName,
                            Integer stockAtAlert,
                            Integer threshold,
                            String status,
                            String resolveRemark,
                            LocalDateTime createdAt,
                            LocalDateTime resolvedAt) {
    }

    /** 闭环工单请求（备注可空）。 */
    public record ResolveAlertRequest(@Size(max = 200, message = "闭环备注过长") String remark) {
    }
}
