package com.retailsuite.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailsuite.ai.dto.AiDtos;
import com.retailsuite.ai.entity.AiDraft;
import com.retailsuite.ai.llm.LlmClient;
import com.retailsuite.ai.mapper.AiDraftMapper;
import com.retailsuite.ai.nlp.ChineseOrderParser;
import com.retailsuite.audit.AuditService;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.purchase.dto.PurchaseDtos;
import com.retailsuite.purchase.service.PurchaseService;
import com.retailsuite.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 自然语言录单：文本 → 结构化草稿 → 人工确认 → 生成真实单据。
 *
 * 三条不可动摇的规则（AI 模块的安全边界）：
 * 1) **AI 不直接改账**：解析结果只落 ai_draft，必须人工点确认才会生成采购单；
 * 2) **只生成采购单**：销售单必须走收银台（涉及收款、库存扣减、退货），不允许一句话产生销售单；
 * 3) **解析来源可追溯**：草稿里记录 source=LLM 还是 RULE，原始文本与解析结果都留存，
 *    出问题时能回答"模型看到了什么、解析成什么、人改成了什么"。
 *
 * 解析优先级：配了模型 → 先试模型（JSON 输出），失败或结果不可用 → 本地规则解析。
 * 这样没配 API Key 的环境也能完整演示这条链路（CI 里跑的就是规则分支）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NlDraftService {

    private static final String SYSTEM_PROMPT = """
            你是进货单解析器。把用户的口语化进货描述解析成 JSON，只输出 JSON：
            {"items":[{"productName":"商品名","barcode":"条码可选","quantity":数量整数,"price":单价可选}]}
            规则：quantity 必填且为正整数；不确定的商品名按原文照抄；不要编造商品与价格。""";

    private final AiDraftMapper draftMapper;
    private final ProductMapper productMapper;
    private final ChineseOrderParser parser;
    private final LlmClient llmClient;
    private final PurchaseService purchaseService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** 解析文本生成草稿（不产生任何单据）。 */
    @Transactional(rollbackFor = Exception.class)
    public AiDtos.DraftView createDraft(Long storeId, AiDtos.DraftRequest request) {
        String type = normalizeType(request.type());
        List<ChineseOrderParser.ParsedItem> parsed = parser.parse(request.text());
        String source = "RULE";

        if (llmClient.configured()) {
            Optional<List<ChineseOrderParser.ParsedItem>> llmItems = parseWithLlm(request.text());
            if (llmItems.isPresent() && !llmItems.get().isEmpty()) {
                parsed = llmItems.get();
                source = "LLM";
            } else {
                log.info("模型解析不可用或结果为空，回退本地规则解析");
            }
        }

        List<AiDtos.DraftItemView> items = new ArrayList<>();
        for (ChineseOrderParser.ParsedItem item : parsed) {
            items.add(resolve(storeId, item));
        }

        AiDraft draft = new AiDraft();
        draft.setStoreId(storeId);
        draft.setDraftType(type);
        draft.setRawText(request.text());
        draft.setParsedJson(writeJson(items));
        draft.setStatus(AiDraft.STATUS_PENDING);
        draft.setSource(source);
        draft.setCreatedBy(UserContext.currentUserId());
        draft.setCreatedAt(LocalDateTime.now());
        draft.setUpdatedAt(LocalDateTime.now());
        draft.setDeleted(0);
        draftMapper.insert(draft);

        auditService.record("AI_DRAFT_CREATE", "ai_draft", String.valueOf(draft.getId()),
                "解析来源=" + source + "，识别 " + items.size() + " 行，文本=" + abbreviate(request.text()));
        return toView(draft, items, null);
    }

    /** 人工确认：草稿 → 真实采购单（草稿状态，需在采购页确认入库才动库存）。 */
    @Transactional(rollbackFor = Exception.class)
    public AiDtos.DraftView confirm(Long storeId, Long draftId, AiDtos.ConfirmRequest request) {
        AiDraft draft = requireDraft(storeId, draftId);
        if (!AiDraft.STATUS_PENDING.equals(draft.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "草稿已处理过（当前状态：" + draft.getStatus() + "）");
        }
        if (AiDraft.TYPE_SALE.equals(draft.getDraftType())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "销售单必须走收银台（涉及收款与库存扣减），AI 只做识别与提示");
        }
        List<AiDtos.DraftItemView> items = readItems(draft);
        List<PurchaseDtos.ItemRequest> purchaseItems = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (AiDtos.DraftItemView item : items) {
            if (item.resolved() && item.quantity() != null && item.quantity() > 0) {
                purchaseItems.add(new PurchaseDtos.ItemRequest(item.productId(), item.quantity(),
                        item.price() == null ? BigDecimal.ZERO : item.price()));
            } else {
                unresolved.add(item.rawText());
            }
        }
        if (purchaseItems.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有可确认的明细，请先在界面上修正未识别的行");
        }
        if (!unresolved.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "还有未识别的行需要人工处理：" + String.join(" / ", unresolved));
        }

        PurchaseDtos.View order = purchaseService.create(storeId, new PurchaseDtos.CreateRequest(
                request == null ? null : request.supplierName(),
                request == null || request.remark() == null ? "由自然语言录入生成" : request.remark(),
                purchaseItems));

        AiDraft update = new AiDraft();
        update.setId(draft.getId());
        update.setStatus(AiDraft.STATUS_CONFIRMED);
        update.setConfirmedBy(UserContext.currentUserId());
        update.setCreatedRefNo(order.orderNo());
        update.setUpdatedAt(LocalDateTime.now());
        draftMapper.updateById(update);

        auditService.record("AI_DRAFT_CONFIRM", "ai_draft", String.valueOf(draft.getId()),
                "生成采购单 " + order.orderNo() + "，明细 " + purchaseItems.size() + " 行");
        return toView(draft, items, "已生成采购单 " + order.orderNo()
                + "（草稿）。去「采购」页确认入库后库存才会增加。");
    }

    @Transactional(rollbackFor = Exception.class)
    public void discard(Long storeId, Long draftId) {
        AiDraft draft = requireDraft(storeId, draftId);
        if (!AiDraft.STATUS_PENDING.equals(draft.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "草稿已处理过，不能重复作废");
        }
        AiDraft update = new AiDraft();
        update.setId(draft.getId());
        update.setStatus(AiDraft.STATUS_DISCARDED);
        update.setUpdatedAt(LocalDateTime.now());
        draftMapper.updateById(update);
        auditService.record("AI_DRAFT_DISCARD", "ai_draft", String.valueOf(draft.getId()), "人工作废草稿");
    }

    public List<AiDtos.DraftView> recent(Long storeId, int limit) {
        List<AiDraft> drafts = draftMapper.selectList(new LambdaQueryWrapper<AiDraft>()
                .eq(AiDraft::getStoreId, storeId)
                .orderByDesc(AiDraft::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 50)));
        List<AiDtos.DraftView> views = new ArrayList<>(drafts.size());
        for (AiDraft draft : drafts) {
            views.add(toView(draft, readItems(draft), null));
        }
        return views;
    }

    // ------------------------------------------------------------------ 内部

    private Optional<List<ChineseOrderParser.ParsedItem>> parseWithLlm(String text) {
        Optional<String> raw = llmClient.chatJson(SYSTEM_PROMPT, text);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(extractJson(raw.get()),
                    new TypeReference<Map<String, Object>>() {
                    });
            Object itemsNode = root.get("items");
            if (!(itemsNode instanceof List<?> list)) {
                return Optional.empty();
            }
            List<ChineseOrderParser.ParsedItem> items = new ArrayList<>();
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                String name = stringOf(map.get("productName"));
                String barcode = stringOf(map.get("barcode"));
                Integer quantity = intOf(map.get("quantity"));
                BigDecimal price = decimalOf(map.get("price"));
                if (quantity != null && (name != null || barcode != null)) {
                    items.add(new ChineseOrderParser.ParsedItem(name == null ? barcode : name, name, barcode, quantity, price));
                }
            }
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (Exception e) {
            log.warn("模型 JSON 解析失败，回退规则解析：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 把解析出来的行匹配到本门店的商品：条码优先，其次名称包含匹配。 */
    private AiDtos.DraftItemView resolve(Long storeId, ChineseOrderParser.ParsedItem item) {
        Product product = null;
        if (item.barcode() != null) {
            product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                    .eq(Product::getStoreId, storeId)
                    .eq(Product::getBarcode, item.barcode())
                    .last("LIMIT 1"));
        }
        if (product == null && item.productKeyword() != null && !item.productKeyword().isBlank()) {
            List<Product> candidates = productMapper.selectList(new LambdaQueryWrapper<Product>()
                    .eq(Product::getStoreId, storeId)
                    .like(Product::getName, item.productKeyword())
                    .last("LIMIT 1"));
            if (!candidates.isEmpty()) {
                product = candidates.get(0);
            }
        }
        String note;
        if (product == null) {
            note = "未匹配到商品，请人工选择或修改文字";
        } else if (item.quantity() == null) {
            note = "未识别数量，请人工补充";
        } else if (item.price() == null) {
            note = "未识别单价，按商品进价 " + product.getPurchasePrice() + " 处理";
        } else {
            note = "OK";
        }
        return new AiDtos.DraftItemView(item.rawText(), item.productKeyword(),
                product == null ? null : product.getId(),
                product == null ? null : product.getName(),
                item.barcode() == null ? (product == null ? null : product.getBarcode()) : item.barcode(),
                item.quantity(),
                item.price() == null ? (product == null ? null : product.getPurchasePrice()) : item.price(),
                product != null && item.quantity() != null,
                note);
    }

    private AiDraft requireDraft(Long storeId, Long draftId) {
        AiDraft draft = draftMapper.selectById(draftId);
        if (draft == null || !draft.getStoreId().equals(storeId)) {
            throw BizException.notFound("草稿");
        }
        return draft;
    }

    private List<AiDtos.DraftItemView> readItems(AiDraft draft) {
        if (draft.getParsedJson() == null || draft.getParsedJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(draft.getParsedJson(), new TypeReference<List<AiDtos.DraftItemView>>() {
            });
        } catch (Exception e) {
            log.warn("草稿解析结果读取失败 draftId={}：{}", draft.getId(), e.getMessage());
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private AiDtos.DraftView toView(AiDraft draft, List<AiDtos.DraftItemView> items, String message) {
        boolean hasUnresolved = items.stream().anyMatch(item -> !item.resolved());
        String text = message;
        if (text == null) {
            text = hasUnresolved
                    ? "识别出 " + items.size() + " 行，其中 " + items.stream().filter(i -> !i.resolved()).count()
                    + " 行需要人工修正（AI 不会替你猜商品）"
                    : "识别出 " + items.size() + " 行，请核对后确认生成采购单";
        }
        return new AiDtos.DraftView(draft.getId(), draft.getDraftType(), draft.getStatus(), draft.getSource(),
                draft.getRawText(), items, hasUnresolved, text, draft.getCreatedRefNo());
    }

    private String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return AiDraft.TYPE_PURCHASE;
        }
        String type = raw.trim().toUpperCase();
        if (!AiDraft.TYPE_PURCHASE.equals(type) && !AiDraft.TYPE_SALE.equals(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "草稿类型只能是 PURCHASE 或 SALE");
        }
        return type;
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private String stringOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Integer intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimalOf(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return value == null ? null : new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
    }

    /** 供测试与前端提示：本模块的能力边界（写在代码里，避免文档与实现漂移）。 */
    public Map<String, Object> capabilities() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("llmConfigured", llmClient.configured());
        map.put("draftTypes", List.of(AiDraft.TYPE_PURCHASE));
        map.put("saleDraftSupported", false);
        map.put("reason", "销售单涉及收款与库存扣减，必须由收银员在收银台逐步确认");
        return map;
    }
}
