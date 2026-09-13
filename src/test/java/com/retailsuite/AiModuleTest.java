package com.retailsuite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.ai.dto.AiDtos;
import com.retailsuite.ai.entity.AiDraft;
import com.retailsuite.ai.mapper.AiDraftMapper;
import com.retailsuite.ai.nlp.ChineseOrderParser;
import com.retailsuite.ai.service.AssistantService;
import com.retailsuite.ai.service.NlDraftService;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.purchase.service.PurchaseService;
import com.retailsuite.sales.dto.SalesDtos;
import com.retailsuite.sales.entity.SaleOrder;
import com.retailsuite.sales.service.SaleService;
import com.retailsuite.store.entity.Store;
import com.retailsuite.store.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 模块：自然语言录单（含人工确认门）与经营助手。
 * 测试环境没有配 API Key，所以这里验的是**本地规则解析 + 规则助手**这条离线分支，
 * 也正是店铺网络异常时真实会走的那条分支。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiModuleTest {

    @Autowired
    private NlDraftService nlDraftService;
    @Autowired
    private AssistantService assistantService;
    @Autowired
    private ChineseOrderParser parser;
    @Autowired
    private ProductService productService;
    @Autowired
    private PurchaseService purchaseService;
    @Autowired
    private SaleService saleService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private AiDraftMapper draftMapper;
    @Autowired
    private StoreMapper storeMapper;

    private Long storeId;

    @BeforeEach
    void setUp() {
        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>().last("LIMIT 1"));
        assertNotNull(store);
        storeId = store.getId();
    }

    @Test
    void 本地规则能解析数量单价与商品名() {
        ProductDtos.View product = createProduct("AI测试椰汁", 0, "5.00", "2.50");

        List<ChineseOrderParser.ParsedItem> parsed = parser.parse("进了 7 瓶" + product.name() + " 单价 2.5");

        assertEquals(1, parsed.size());
        assertEquals(7, parsed.get(0).quantity());
        assertEquals(0, new BigDecimal("2.5").compareTo(parsed.get(0).price()));
        assertEquals(product.name(), parsed.get(0).productKeyword());
        assertTrue(parsed.get(0).usable());

        AiDtos.DraftView draft = nlDraftService.createDraft(storeId, new AiDtos.DraftRequest(
                "进了 7 瓶" + product.name() + " 单价 2.5", null));
        assertEquals(AiDraft.STATUS_PENDING, draft.status());
        assertEquals("RULE", draft.source(), "未配置模型时必须走本地规则解析");
        assertEquals(1, draft.items().size());
        assertTrue(draft.items().get(0).resolved());
        assertEquals(product.id(), draft.items().get(0).productId());
        assertEquals(7, draft.items().get(0).quantity());
    }

    @Test
    void 支持条码识别与多行解析() {
        // 用真实的 13 位纯数字条码（EAN-13 就是纯数字），解析器只提取数字段
        ProductDtos.View product = createProductWithNumericBarcode("AI测试饼干", 0, "9.00", "5.00");

        List<ChineseOrderParser.ParsedItem> parsed = parser.parse(
                product.barcode() + " 20 个 单价 5.5，另 进了 3 袋" + product.name());

        assertEquals(2, parsed.size(), "应按中文逗号切成两条：" + parsed);
        assertEquals(product.barcode(), parsed.get(0).barcode());
        assertEquals(20, parsed.get(0).quantity());
        assertEquals(0, new BigDecimal("5.5").compareTo(parsed.get(0).price()));
        assertEquals(3, parsed.get(1).quantity());
        assertEquals(product.name(), parsed.get(1).productKeyword());
    }

    @Test
    void 识别不出的商品不会被瞎猜_确认时被拒绝() {
        AiDtos.DraftView draft = nlDraftService.createDraft(storeId, new AiDtos.DraftRequest(
                "进了 5 瓶完全不存在的饮料", null));

        assertEquals(1, draft.items().size());
        assertFalse(draft.items().get(0).resolved(), "匹配不到商品就必须标注为未识别");
        assertTrue(draft.hasUnresolved());

        BizException error = assertThrows(BizException.class,
                () -> nlDraftService.confirm(storeId, draft.id(), null));
        assertEquals(ErrorCode.BAD_REQUEST, error.getCode());
        assertTrue(error.getMessage().contains("未识别"), error.getMessage());
    }

    @Test
    void 确认草稿生成采购单但不动库存_入库仍需采购页确认() {
        ProductDtos.View product = createProduct("AI测试牛奶", 10, "6.00", "3.00");
        int stockBefore = product.stock();
        AiDtos.DraftView draft = nlDraftService.createDraft(storeId, new AiDtos.DraftRequest(
                "进了 12 瓶" + product.name() + " 单价 3.2", null));

        AiDtos.DraftView confirmed = nlDraftService.confirm(storeId, draft.id(), null);

        assertEquals(AiDraft.STATUS_CONFIRMED, confirmed.status());
        assertNotNull(confirmed.createdRefNo());
        assertEquals(stockBefore, productMapper.selectById(product.id()).getStock(),
                "AI 草稿确认只生成采购单（草稿态），不能直接改库存");

        // 走正常采购流程确认入库，库存才增加 —— 这是"AI 不直接改账"的完整证明
        Long purchaseOrderId = purchaseService.page(storeId, "DRAFT", 1, 50).records().stream()
                .filter(row -> row.orderNo().equals(confirmed.createdRefNo()))
                .findFirst().orElseThrow().id();
        purchaseService.confirm(storeId, purchaseOrderId);

        assertEquals(stockBefore + 12, productMapper.selectById(product.id()).getStock());
    }

    @Test
    void 销售类草稿不允许由AI生成单据() {
        ProductDtos.View product = createProduct("AI测试汽水", 20, "4.00", "2.00");
        AiDtos.DraftView draft = nlDraftService.createDraft(storeId, new AiDtos.DraftRequest(
                "卖了 2 瓶" + product.name(), AiDraft.TYPE_SALE));

        BizException error = assertThrows(BizException.class,
                () -> nlDraftService.confirm(storeId, draft.id(), null));

        assertEquals(ErrorCode.BAD_REQUEST, error.getCode());
        assertTrue(error.getMessage().contains("收银台"), error.getMessage());
        assertFalse((boolean) nlDraftService.capabilities().get("saleDraftSupported"));
    }

    @Test
    void 草稿不能重复确认也不能重复作废() {
        ProductDtos.View product = createProduct("AI测试咖啡", 0, "8.00", "4.00");
        AiDtos.DraftView draft = nlDraftService.createDraft(storeId, new AiDtos.DraftRequest(
                "进了 4 盒" + product.name() + " 单价 4.5", null));
        nlDraftService.confirm(storeId, draft.id(), null);

        BizException confirmAgain = assertThrows(BizException.class,
                () -> nlDraftService.confirm(storeId, draft.id(), null));
        assertEquals(ErrorCode.CONFLICT, confirmAgain.getCode());

        BizException discard = assertThrows(BizException.class, () -> nlDraftService.discard(storeId, draft.id()));
        assertEquals(ErrorCode.CONFLICT, discard.getCode());

        assertEquals(1, draftMapper.selectCount(new LambdaQueryWrapper<AiDraft>()
                .eq(AiDraft::getId, draft.id())), "草稿只有一条记录，状态变更而不是新增");
    }

    @Test
    void 助手能回答营业额问题() {
        ProductDtos.View product = createProduct("AI测试助手商品", 50, "10.00", "5.00");
        saleService.checkout(storeId, new SalesDtos.CheckoutRequest("REQ-" + java.util.UUID.randomUUID(),
                "散客", List.of(new SalesDtos.ItemRequest(product.id(), 2, null)),
                BigDecimal.ZERO, SaleOrder.PAY_CASH, null));

        AiDtos.AskResponse answer = assistantService.ask(storeId, "今天卖了多少");

        assertEquals("RULE", answer.source(), "未配置模型时走本地规则分支");
        assertTrue(answer.answer().contains("营业额"), answer.answer());
        assertTrue(answer.toolsUsed().contains("sales_summary"));
        assertFalse(answer.steps().isEmpty(), "助手要能说明自己是怎么得出结论的");
    }

    @Test
    void 助手能回答库存预警与单品库存() {
        ProductDtos.View lowStock = createProduct("AI测试低库存商品", 2, "3.00", "1.00");

        AiDtos.AskResponse warning = assistantService.ask(storeId, "哪些商品需要补货");
        assertTrue(warning.answer().contains(lowStock.name()), warning.answer());

        AiDtos.AskResponse stock = assistantService.ask(storeId, lowStock.name() + " 还有多少库存");
        assertTrue(stock.answer().contains("库存"), stock.answer());
        assertTrue(stock.answer().contains(lowStock.name()), stock.answer());
    }

    @Test
    void 助手对对账问题能给出结论() {
        AiDtos.AskResponse answer = assistantService.ask(storeId, "今天对账有差异吗");

        assertTrue(answer.toolsUsed().contains("reconcile"));
        assertTrue(answer.answer().contains("对账"), answer.answer());
    }

    @Test
    void 助手遇到不会的问题会说清能力边界而不是编造() {
        AiDtos.AskResponse answer = assistantService.ask(storeId, "帮我预测下个月的销量");

        assertTrue(answer.answer().contains("我可以回答"), "答不了就说清能力边界：" + answer.answer());
        assertTrue(answer.toolsUsed().isEmpty());
    }

    private ProductDtos.View createProduct(String name, int initStock, String salePrice, String purchasePrice) {
        return productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                "BAR-" + System.nanoTime(), "规格", "件",
                new BigDecimal(purchasePrice), new BigDecimal(salePrice), initStock, 10));
    }

    /** 纯数字 13 位条码（EAN-13 风格）：用于验证条码识别路径。 */
    private ProductDtos.View createProductWithNumericBarcode(String name, int initStock,
                                                             String salePrice, String purchasePrice) {
        String barcode = "69" + String.format("%011d", Math.abs(System.nanoTime() % 100_000_000_000L));
        return productService.create(storeId, new ProductDtos.CreateRequest(name, null,
                barcode, "规格", "件",
                new BigDecimal(purchasePrice), new BigDecimal(salePrice), initStock, 10));
    }
}
