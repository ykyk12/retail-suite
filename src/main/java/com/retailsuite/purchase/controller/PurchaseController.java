package com.retailsuite.purchase.controller;

import com.retailsuite.common.ApiResponse;
import com.retailsuite.common.PageResult;
import com.retailsuite.purchase.dto.PurchaseDtos;
import com.retailsuite.purchase.service.PurchaseService;
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

/** 采购接口。收银员没有 purchase:* 权限，看不到也改不了进货数据（权限码在数据里已体现）。 */
@RestController
@RequestMapping("/api/purchases")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchaseService;

    @PostMapping
    @RequiresPermission("purchase:write")
    @Operation(summary = "新建采购单（草稿，不动库存）")
    public ApiResponse<PurchaseDtos.View> create(@Valid @RequestBody PurchaseDtos.CreateRequest request) {
        return ApiResponse.ok(purchaseService.create(UserContext.requireStoreId(), request));
    }

    @PostMapping("/{orderId}/confirm")
    @RequiresPermission("purchase:write")
    @Operation(summary = "确认入库（增加库存并写流水，不可重复确认）")
    public ApiResponse<PurchaseDtos.View> confirm(@PathVariable Long orderId) {
        return ApiResponse.ok(purchaseService.confirm(UserContext.requireStoreId(), orderId));
    }

    @PostMapping("/{orderId}/cancel")
    @RequiresPermission("purchase:write")
    @Operation(summary = "取消采购单（仅草稿可取消）")
    public ApiResponse<Void> cancel(@PathVariable Long orderId) {
        purchaseService.cancel(UserContext.requireStoreId(), orderId);
        return ApiResponse.ok();
    }

    @GetMapping
    @RequiresPermission("purchase:read")
    @Operation(summary = "采购单分页查询")
    public ApiResponse<PageResult<PurchaseDtos.View>> page(@RequestParam(required = false) String status,
                                                           @RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(purchaseService.page(UserContext.requireStoreId(), status, page, size));
    }

    @GetMapping("/{orderId}")
    @RequiresPermission("purchase:read")
    @Operation(summary = "采购单详情（含明细）")
    public ApiResponse<PurchaseDtos.View> detail(@PathVariable Long orderId) {
        return ApiResponse.ok(purchaseService.detail(UserContext.requireStoreId(), orderId));
    }
}
