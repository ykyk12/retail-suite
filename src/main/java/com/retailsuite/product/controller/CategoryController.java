package com.retailsuite.product.controller;

import com.retailsuite.common.ApiResponse;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.security.RequiresPermission;
import com.retailsuite.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 商品分类接口。 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final ProductService productService;

    @GetMapping
    @RequiresPermission("product:read")
    @Operation(summary = "分类列表")
    public ApiResponse<List<ProductDtos.CategoryView>> list() {
        return ApiResponse.ok(productService.categories(UserContext.requireStoreId()));
    }

    @PostMapping
    @RequiresPermission("category:write")
    @Operation(summary = "新建分类")
    public ApiResponse<ProductDtos.CategoryView> create(@Valid @RequestBody ProductDtos.CategoryRequest request) {
        return ApiResponse.ok(productService.createCategory(UserContext.requireStoreId(), request));
    }

    @PutMapping("/{categoryId}")
    @RequiresPermission("category:write")
    @Operation(summary = "修改分类")
    public ApiResponse<Void> update(@PathVariable Long categoryId,
                                    @Valid @RequestBody ProductDtos.CategoryRequest request) {
        productService.updateCategory(UserContext.requireStoreId(), categoryId, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{categoryId}")
    @RequiresPermission("category:write")
    @Operation(summary = "删除分类（分类下有商品时拒绝）")
    public ApiResponse<Void> delete(@PathVariable Long categoryId) {
        productService.deleteCategory(UserContext.requireStoreId(), categoryId);
        return ApiResponse.ok();
    }
}
