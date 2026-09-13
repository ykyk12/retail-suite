package com.retailsuite.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 采购相关 DTO。 */
public final class PurchaseDtos {

    private PurchaseDtos() {
    }

    public record CreateRequest(
            @Size(max = 128, message = "供应商名称过长")
            String supplierName,
            @Size(max = 200, message = "备注过长")
            String remark,
            @NotEmpty(message = "采购明细不能为空")
            @Valid
            List<ItemRequest> items) {
    }

    public record ItemRequest(
            @NotNull(message = "商品不能为空")
            Long productId,
            @NotNull(message = "数量不能为空")
            @Min(value = 1, message = "数量必须大于 0")
            Integer quantity,
            @NotNull(message = "进价不能为空")
            @DecimalMin(value = "0", message = "进价不能为负")
            BigDecimal unitCost,
            /** 生产日期（保质期商品建议填：到期日 = 生产日期 + 保质期天数） */
            java.time.LocalDate productionDate,
            /** 保质期天数（留空则用商品档案上的保质期） */
            @Min(value = 0, message = "保质期天数不能为负")
            Integer shelfLifeDays) {

        /** 兼容旧的三参数调用（不带批次信息）。 */
        public ItemRequest(Long productId, Integer quantity, BigDecimal unitCost) {
            this(productId, quantity, unitCost, null, null);
        }
    }

    public record ItemView(Long id,
                           Long productId,
                           String productName,
                           Integer quantity,
                           BigDecimal unitCost,
                           BigDecimal amount,
                           java.time.LocalDate productionDate,
                           Integer shelfLifeDays) {
    }

    public record View(Long id,
                       String orderNo,
                       String supplierName,
                       Integer itemCount,
                       BigDecimal totalAmount,
                       String status,
                       String statusText,
                       String remark,
                       LocalDateTime confirmedAt,
                       LocalDateTime createdAt,
                       List<ItemView> items) {
    }
}
