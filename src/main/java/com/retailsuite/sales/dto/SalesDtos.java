package com.retailsuite.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 收银与退货 DTO。 */
public final class SalesDtos {

    private SalesDtos() {
    }

    /**
     * 结算请求。
     * requestId 由收银前端生成（UUID），是幂等键：同一个 requestId 无论提交多少次，只会产生一笔订单。
     */
    public record CheckoutRequest(
            @NotBlank(message = "requestId 不能为空（幂等键）")
            @Size(max = 64, message = "requestId 过长")
            String requestId,
            @Size(max = 64, message = "客户名称过长")
            String customerName,
            @NotEmpty(message = "购物车不能为空")
            @Valid
            List<ItemRequest> items,
            @DecimalMin(value = "0", message = "折扣不能为负")
            BigDecimal discountAmount,
            @NotBlank(message = "支付方式不能为空")
            String payMethod,
            @Size(max = 200, message = "备注过长")
            String remark) {
    }

    public record ItemRequest(
            @NotNull(message = "商品不能为空")
            Long productId,
            @NotNull(message = "数量不能为空")
            @Min(value = 1, message = "数量必须大于 0")
            Integer quantity,
            /** 可选：允许收银员改价（例如临期打折）；不传则用商品当前售价 */
            @DecimalMin(value = "0", message = "单价不能为负")
            BigDecimal unitPrice) {
    }

    public record RefundItemRequest(
            @NotNull(message = "订单明细不能为空")
            Long orderItemId,
            @NotNull(message = "退货数量不能为空")
            @Min(value = 1, message = "退货数量必须大于 0")
            Integer quantity) {
    }

    public record RefundRequest(
            @NotEmpty(message = "退货明细不能为空")
            @Valid
            List<RefundItemRequest> items,
            @Size(max = 200, message = "备注过长")
            String remark) {
    }

    public record ItemView(Long id,
                           Long productId,
                           String productName,
                           String barcode,
                           Integer quantity,
                           Integer refundedQuantity,
                           BigDecimal unitPrice,
                           BigDecimal amount) {
    }

    public record View(Long id,
                       String orderNo,
                       String requestId,
                       String customerName,
                       Integer itemCount,
                       BigDecimal totalAmount,
                       BigDecimal discountAmount,
                       BigDecimal payAmount,
                       BigDecimal refundAmount,
                       String payMethod,
                       String status,
                       String statusText,
                       String remark,
                       LocalDateTime createdAt,
                       /** true 表示这次请求命中了幂等（返回的是首次下单的结果，没有重复扣库存） */
                       boolean duplicated,
                       List<ItemView> items) {
    }
}
