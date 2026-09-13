package com.retailsuite.product.controller;

import com.retailsuite.common.ApiResponse;
import com.retailsuite.common.PageResult;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.security.RequiresPermission;
import com.retailsuite.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品接口。
 * 所有查询都用 UserContext 里的 storeId 过滤——这是数据隔离的落点，
 * 客户端无法通过传参查看别的门店数据（传了也没用，服务层只认登录态里的门店）。
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @RequiresPermission("product:read")
    @Operation(summary = "商品分页查询", description = "支持关键字（名称/条码）、分类、低库存筛选")
    public ApiResponse<PageResult<ProductDtos.View>> page(@RequestParam(required = false) String keyword,
                                                          @RequestParam(required = false) Long categoryId,
                                                          @RequestParam(required = false) Boolean lowStockOnly,
                                                          @RequestParam(defaultValue = "1") long page,
                                                          @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(productService.page(UserContext.requireStoreId(), keyword, categoryId, lowStockOnly, page, size));
    }

    @GetMapping("/{productId}")
    @RequiresPermission("product:read")
    @Operation(summary = "商品详情")
    public ApiResponse<ProductDtos.View> detail(@PathVariable Long productId) {
        return ApiResponse.ok(productService.detail(UserContext.requireStoreId(), productId));
    }

    @GetMapping("/barcode/{barcode}")
    @RequiresPermission("product:read")
    @Operation(summary = "条码查询（收银台扫码用）")
    public ApiResponse<ProductDtos.View> byBarcode(@PathVariable String barcode) {
        return ApiResponse.ok(productService.findByBarcode(UserContext.requireStoreId(), barcode));
    }

    @PostMapping
    @RequiresPermission("product:write")
    @Operation(summary = "新建商品（可带期初库存，会写入库存流水）")
    public ApiResponse<ProductDtos.View> create(@Valid @RequestBody ProductDtos.CreateRequest request) {
        return ApiResponse.ok(productService.create(UserContext.requireStoreId(), request));
    }

    @PutMapping("/{productId}")
    @RequiresPermission("product:write")
    @Operation(summary = "修改商品（不含库存；库存只能通过库存服务变动）")
    public ApiResponse<ProductDtos.View> update(@PathVariable Long productId,
                                                @Valid @RequestBody ProductDtos.UpdateRequest request) {
        return ApiResponse.ok(productService.update(UserContext.requireStoreId(), productId, request));
    }

    @PatchMapping("/{productId}/status")
    @RequiresPermission("product:write")
    @Operation(summary = "上架/停售")
    public ApiResponse<Void> toggleStatus(@PathVariable Long productId, @RequestParam Integer status) {
        productService.toggleStatus(UserContext.requireStoreId(), productId, status);
        return ApiResponse.ok();
    }
}
