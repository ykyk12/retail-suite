package com.retailsuite.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
                           LocalDateTime createdAt) {
    }

    public record LowStockItem(Long productId,
                               String name,
                               String barcode,
                               Integer stock,
                               Integer lowStockThreshold,
                               String unit) {
    }

    public record FlowPage(List<FlowView> records, long total, long page, long size) {
    }
}
