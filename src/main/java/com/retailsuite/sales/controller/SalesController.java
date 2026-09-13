package com.retailsuite.sales.controller;

import com.retailsuite.common.ApiResponse;
import com.retailsuite.common.PageResult;
import com.retailsuite.sales.dto.SalesDtos;
import com.retailsuite.sales.service.SaleService;
import com.retailsuite.security.RequiresPermission;
import com.retailsuite.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 收银接口（收银台核心链路）。 */
@RestController
@RequestMapping("/api/sales")
@RequiredArgsConstructor
public class SalesController {

    private final SaleService saleService;

    @PostMapping("/checkout")
    @RequiresPermission("sale:create")
    @Operation(summary = "收银结算（幂等：同一 requestId 只产生一笔订单）",
            description = "库存不足会整笔回滚；同一 requestId 重复提交返回首次结果并标记 duplicated=true")
    public ApiResponse<SalesDtos.View> checkout(@Valid @RequestBody SalesDtos.CheckoutRequest request) {
        return ApiResponse.ok(saleService.checkout(UserContext.requireStoreId(), request));
    }

    @PostMapping("/{orderId}/refund")
    @RequiresPermission("refund:create")
    @Operation(summary = "退货（支持部分退，库存回补并写流水）")
    public ApiResponse<SalesDtos.View> refund(@PathVariable Long orderId,
                                              @Valid @RequestBody SalesDtos.RefundRequest request) {
        return ApiResponse.ok(saleService.refund(UserContext.requireStoreId(), orderId, request));
    }

    @GetMapping
    @RequiresPermission("sale:read")
    @Operation(summary = "订单分页查询（支持状态与关键字）")
    public ApiResponse<PageResult<SalesDtos.View>> page(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(saleService.page(UserContext.requireStoreId(), status, keyword, page, size));
    }

    @GetMapping("/{orderId}")
    @RequiresPermission("sale:read")
    @Operation(summary = "订单详情（含明细与已退数量）")
    public ApiResponse<SalesDtos.View> detail(@PathVariable Long orderId) {
        return ApiResponse.ok(saleService.detail(UserContext.requireStoreId(), orderId));
    }
}
