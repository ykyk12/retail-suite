package com.retailsuite.inventory.controller;

import com.retailsuite.common.ApiResponse;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.entity.InventoryFlow;
import com.retailsuite.inventory.service.ExpiryService;
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
import java.util.Map;

/** 库存接口：预警、流水、批次与保质期、盘点、报损。 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final ExpiryService expiryService;

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

    @GetMapping("/batches/{productId}")
    @RequiresPermission("inventory:read")
    @Operation(summary = "某商品的批次列表（含生产日期、到期日、剩余数量与剩余天数）")
    public ApiResponse<List<InventoryDtos.BatchView>> batches(@PathVariable Long productId) {
        return ApiResponse.ok(expiryService.batchesOf(UserContext.requireStoreId(), productId));
    }

    @GetMapping("/expiring")
    @RequiresPermission("inventory:read")
    @Operation(summary = "临期批次（默认 30 天内到期，近效期优先）")
    public ApiResponse<List<InventoryDtos.BatchView>> expiring(@RequestParam(required = false) Integer days) {
        return ApiResponse.ok(expiryService.expiring(UserContext.requireStoreId(), days));
    }

    @GetMapping("/expired")
    @RequiresPermission("inventory:read")
    @Operation(summary = "已过期批次（仍有库存，必须下架报损）")
    public ApiResponse<List<InventoryDtos.BatchView>> expired() {
        return ApiResponse.ok(expiryService.expired(UserContext.requireStoreId()));
    }

    @GetMapping("/expiry-summary")
    @RequiresPermission("inventory:read")
    @Operation(summary = "临期与过期汇总（数量与按成本价计算的压货金额）")
    public ApiResponse<InventoryDtos.ExpirySummary> expirySummary(@RequestParam(required = false) Integer days) {
        return ApiResponse.ok(expiryService.summary(UserContext.requireStoreId(), days));
    }

    @GetMapping("/batch-mismatch")
    @RequiresPermission("inventory:read")
    @Operation(summary = "批次与聚合库存一致性核对（正常应返回空数组）")
    public ApiResponse<List<Map<String, Object>>> batchMismatch() {
        return ApiResponse.ok(inventoryService.batchMismatches(UserContext.requireStoreId()));
    }

    @PostMapping("/adjust")
    @RequiresPermission("inventory:adjust")
    @Operation(summary = "库存盘点调整（必须填原因，写流水与批次）")
    public ApiResponse<InventoryDtos.FlowView> adjust(@Valid @RequestBody InventoryDtos.AdjustRequest request) {
        Long storeId = UserContext.requireStoreId();
        InventoryFlow flow = inventoryService.adjust(storeId, request);
        return ApiResponse.ok(toView(flow));
    }

    @PostMapping("/loss")
    @RequiresPermission("inventory:loss")
    @Operation(summary = "报损出库（过期/破损下架，按近效期先出扣批次，必须填原因）")
    public ApiResponse<InventoryDtos.FlowView> loss(@Valid @RequestBody InventoryDtos.LossRequest request) {
        Long storeId = UserContext.requireStoreId();
        return ApiResponse.ok(toView(inventoryService.loss(storeId, request)));
    }

    private InventoryDtos.FlowView toView(InventoryFlow flow) {
        return new InventoryDtos.FlowView(flow.getId(), flow.getProductId(), null, flow.getType(),
                flow.getQuantity(), flow.getBeforeStock(), flow.getAfterStock(), flow.getRefType(),
                flow.getRefNo(), flow.getRemark(), flow.getBatchId(), flow.getCreatedAt());
    }
}
