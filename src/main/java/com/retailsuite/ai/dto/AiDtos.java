package com.retailsuite.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** AI 模块 DTO。 */
public final class AiDtos {

    private AiDtos() {
    }

    /** 自然语言录单请求。type 默认 PURCHASE（销售单不允许 AI 直接生成，必须走收银台）。 */
    public record DraftRequest(
            @NotBlank(message = "请输入要录入的内容")
            @Size(max = 1000, message = "文本过长，请分批录入")
            String text,
            String type) {
    }

    /** 草稿明细：resolved=false 表示这条没匹配到商品，需要人工在界面上选择或改文字。 */
    public record DraftItemView(String rawText,
                                String productKeyword,
                                Long productId,
                                String productName,
                                String barcode,
                                Integer quantity,
                                BigDecimal price,
                                boolean resolved,
                                String note) {
    }

    public record DraftView(Long id,
                            String draftType,
                            String status,
                            String source,
                            String rawText,
                            List<DraftItemView> items,
                            boolean hasUnresolved,
                            String message,
                            String createdRefNo) {
    }

    public record ConfirmRequest(String supplierName, String remark) {
    }

    public record AskRequest(
            @NotBlank(message = "请输入问题")
            @Size(max = 500, message = "问题过长")
            String question) {
    }

    /** 经营助手回答。source 标明是模型回答还是本地规则回答，便于用户判断可信度。 */
    public record AskResponse(String answer,
                              String source,
                              List<String> toolsUsed,
                              List<String> steps) {
    }
}
