package com.retailsuite.ai.controller;

import com.retailsuite.ai.dto.AiDtos;
import com.retailsuite.ai.service.AssistantService;
import com.retailsuite.ai.service.NlDraftService;
import com.retailsuite.common.ApiResponse;
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

/**
 * AI 接口：自然语言录单（草稿 + 人工确认）与经营助手（只读问答）。
 * 全部需要 ai:use 权限；写操作只发生在"人工确认草稿"这一步。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final NlDraftService nlDraftService;
    private final AssistantService assistantService;

    @GetMapping("/capabilities")
    @RequiresPermission("ai:use")
    @Operation(summary = "AI 模块能力与边界（含当前是否接了模型）")
    public ApiResponse<Map<String, Object>> capabilities() {
        return ApiResponse.ok(nlDraftService.capabilities());
    }

    @PostMapping("/drafts")
    @RequiresPermission("ai:use")
    @Operation(summary = "自然语言录单：文本 → 草稿（不产生单据，需人工确认）",
            description = "例：\"进了 10 瓶农夫山泉 单价 1.2\"。解析不出商品的行会标记出来让人工修正")
    public ApiResponse<AiDtos.DraftView> createDraft(@Valid @RequestBody AiDtos.DraftRequest request) {
        return ApiResponse.ok(nlDraftService.createDraft(UserContext.requireStoreId(), request));
    }

    @GetMapping("/drafts")
    @RequiresPermission("ai:use")
    @Operation(summary = "最近的录单草稿")
    public ApiResponse<List<AiDtos.DraftView>> drafts(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(nlDraftService.recent(UserContext.requireStoreId(), limit));
    }

    @PostMapping("/drafts/{draftId}/confirm")
    @RequiresPermission("ai:use")
    @Operation(summary = "人工确认草稿 → 生成采购单（草稿态，入库仍需在采购页确认）")
    public ApiResponse<AiDtos.DraftView> confirm(@PathVariable Long draftId,
                                                 @RequestBody(required = false) AiDtos.ConfirmRequest request) {
        return ApiResponse.ok(nlDraftService.confirm(UserContext.requireStoreId(), draftId, request));
    }

    @PostMapping("/drafts/{draftId}/discard")
    @RequiresPermission("ai:use")
    @Operation(summary = "作废草稿（不产生任何单据）")
    public ApiResponse<Void> discard(@PathVariable Long draftId) {
        nlDraftService.discard(UserContext.requireStoreId(), draftId);
        return ApiResponse.ok();
    }

    @PostMapping("/assistant/ask")
    @RequiresPermission("ai:use")
    @Operation(summary = "经营助手（只读问答：营业额/畅销/库存预警/单品库存/对账）",
            description = "只挂只读工具，不产生任何写操作；未配置模型时走本地规则意图识别")
    public ApiResponse<AiDtos.AskResponse> ask(@Valid @RequestBody AiDtos.AskRequest request) {
        return ApiResponse.ok(assistantService.ask(UserContext.requireStoreId(), request.question()));
    }
}
