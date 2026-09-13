package com.retailsuite.inventory.controller;

import com.retailsuite.common.ApiResponse;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.entity.InventoryFlow;
import com.retailsuite.inventory.service.InventoryService;
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

import java.util.List;

/** 库存接口：查看预警、查看流水、盘点调整。 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/low-stock")
    @RequiresPermission("inventory:read")
    @Operation(summary = "库存预警列表（库存 ≤ 各自阈值）")
    public ApiResponse<List<InventoryDtos.LowStockItem>> lowStock() {
        return ApiResponse.ok(inventoryService.lowStockItems(UserContext.requireStoreId()));
    }

    @GetMapping("/flows/{productId}")
    @RequiresPermission("inventory:read")
    @Operation(summary = "某商品的库存流水（可回答“库存为什么变了”）")
    public ApiResponse<List<InventoryDtos.FlowView>> flows(@PathVariable Long productId,
                                                           @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(inventoryService.recentFlows(UserContext.requireStoreId(), productId, limit));
    }

    @PostMapping("/adjust")
    @RequiresPermission("inventory:adjust")
    @Operation(summary = "库存盘点调整（必须填原因，写流水）")
    public ApiResponse<InventoryDtos.FlowView> adjust(@Valid @RequestBody InventoryDtos.AdjustRequest request) {
        Long storeId = UserContext.requireStoreId();
        InventoryFlow flow = inventoryService.adjust(storeId, request);
        return ApiResponse.ok(new InventoryDtos.FlowView(flow.getId(), flow.getProductId(), null, flow.getType(),
                flow.getQuantity(), flow.getBeforeStock(), flow.getAfterStock(), flow.getRefType(),
                flow.getRefNo(), flow.getRemark(), flow.getCreatedAt()));
    }
}
