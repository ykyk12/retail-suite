package com.retailsuite.steward.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailsuite.audit.AuditService;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.config.AppProperties;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.service.ExpiryService;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.service.ReportService;
import com.retailsuite.steward.dto.StewardDtos;
import com.retailsuite.steward.entity.StewardReport;
import com.retailsuite.steward.mapper.StewardReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管家主动巡检：把"店长该发现的问题"替他发现一遍，并落成一份可执行的结构化日报。
 *
 * 巡检项（每一项都带具体数字，不写"建议关注库存"这种废话）：
 * 1. 过期批次 —— 必须立刻下架报损（食品安全红线）
 * 2. 临期批次 —— 还剩多少天、压了多少钱
 * 3. 断货风险 —— 按近期销速还能卖几天
 * 4. 补货建议 —— 补多少件、约多少钱，**可一键转采购单草稿**
 * 5. 滞销商品 —— 多久没卖、压货金额
 * 6. 毛利异常 —— 毛利率过低或为负
 * 7. 账实不符 —— 销售数量与出库数量对不上（可能有人绕开收银改动库存）
 * 8. 批次与聚合库存不符 —— 批次台账自身的一致性
 *
 * 幂等：唯一键 (store_id, report_date)，同一天重复巡检覆盖同一行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StewardInspectionService {

    public static final String SOURCE_SCHEDULED = "SCHEDULED";
    public static final String SOURCE_MANUAL = "MANUAL";

    /** 断货判定：按近期销速，剩余可卖天数低于这个值就报"断货风险" */
    private static final int STOCKOUT_DAYS = 3;

    /**
     * 单条发现最多列多少明细。
     *
     * 这是**有意的上限**，不是偷懒：报告是给店长做决策的摘要，不是数据导出。
     * 一个 500 个商品的门店，过期批次可能上百条——全塞进去既没人看得完，
     * 也会把落库的 JSON 撑爆（这一点在 CI 上真实踩到过：findings 超过列容量直接让巡检写入失败）。
     * 明细超过上限时只保留前 N 条，并在 metrics.omittedCount 里说明还有多少条，
     * 前端据此提示"更多请到临期页查看"。
     */
    private static final int MAX_ITEMS_PER_FINDING = 20;

    /** 落库 JSON 的兜底上限：超过就把明细剥掉再存，宁可报告变简略，也不能让定时任务写失败。 */
    private static final int MAX_FINDINGS_JSON_CHARS = 200_000;

    /** 发现编码 → 中文名，用于拼一句话总结 */
    private static final Map<String, String> CODE_LABELS = Map.of(
            "EXPIRED_BATCH", "过期批次",
            "EXPIRING_BATCH", "临期批次",
            "STOCKOUT_RISK", "断货风险",
            "BATCH_MISMATCH", "批次与库存不符",
            "RECONCILE_DIFF", "账实差异",
            "REORDER", "补货建议",
            "SLOW_MOVER", "滞销商品",
            "MARGIN_ANOMALY", "毛利异常");

    private final StewardReportMapper reportMapper;
    private final StewardAnalysisService analysisService;
    private final ExpiryService expiryService;
    private final InventoryService inventoryService;
    private final ReportService reportService;
    private final AuditService auditService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    /** 执行一次巡检并落库（同一天覆盖式幂等）。 */
    @Transactional
    public StewardDtos.ReportView inspect(Long storeId, String source) {
        LocalDate today = LocalDate.now();
        List<StewardDtos.Finding> findings = collect(storeId);
        String headline = headline(findings);
        int highCount = (int) findings.stream().filter(f -> "HIGH".equals(f.severity())).count();

        StewardReport existing = reportMapper.selectOne(new LambdaQueryWrapper<StewardReport>()
                .eq(StewardReport::getStoreId, storeId)
                .eq(StewardReport::getReportDate, today)
                .last("LIMIT 1"));
        StewardReport report = existing == null ? new StewardReport() : existing;
        report.setStoreId(storeId);
        report.setReportDate(today);
        report.setSource(source);
        report.setHeadline(headline);
        report.setFindingCount(findings.size());
        report.setHighCount(highCount);
        report.setFindings(toJson(findings));
        report.setUpdatedAt(LocalDateTime.now());
        report.setDeleted(0);
        if (existing == null) {
            report.setGeneratedAt(LocalDateTime.now());
            reportMapper.insert(report);
        } else {
            reportMapper.updateById(report);
        }
        auditService.record("STEWARD_INSPECT", "STEWARD_REPORT", String.valueOf(report.getId()),
                "门店 " + storeId + " 巡检（" + source + "）：" + findings.size() + " 项发现，其中 " + highCount + " 项需立即处理");
        return toView(report);
    }

    /** 最近一份日报（没有就抛 404，让前端明确提示"还没生成过"）。 */
    public StewardDtos.ReportView latest(Long storeId) {
        StewardReport report = reportMapper.selectOne(new LambdaQueryWrapper<StewardReport>()
                .eq(StewardReport::getStoreId, storeId)
                .orderByDesc(StewardReport::getReportDate)
                .last("LIMIT 1"));
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "还没有巡检报告，先执行一次巡检（POST /api/steward/inspect）");
        }
        return toView(report);
    }

    /** 历史日报列表（按日期倒序）。 */
    public List<StewardDtos.ReportView> recent(Long storeId, int limit) {
        int size = limit <= 0 ? 10 : Math.min(limit, 50);
        List<StewardReport> reports = reportMapper.selectList(new LambdaQueryWrapper<StewardReport>()
                .eq(StewardReport::getStoreId, storeId)
                .orderByDesc(StewardReport::getReportDate)
                .last("LIMIT " + size));
        List<StewardDtos.ReportView> views = new ArrayList<>(reports.size());
        for (StewardReport report : reports) {
            views.add(toView(report));
        }
        return views;
    }

    /** 按 id 取日报（同时校验门店，避免跨门店读到别家经营数据）。 */
    public StewardDtos.ReportView find(Long storeId, Long reportId) {
        StewardReport report = reportMapper.selectById(reportId);
        if (report == null || !storeId.equals(report.getStoreId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "巡检报告不存在：" + reportId);
        }
        return toView(report);
    }

    // ------------------------------------------------------------------ 巡检项

    private List<StewardDtos.Finding> collect(Long storeId) {
        List<StewardDtos.Finding> findings = new ArrayList<>();
        addIfPresent(findings, expiredBatches(storeId));
        addIfPresent(findings, expiringBatches(storeId));
        addIfPresent(findings, stockoutRisks(storeId));
        addIfPresent(findings, batchMismatch(storeId));
        addIfPresent(findings, reconcileDiff(storeId));
        addIfPresent(findings, reorder(storeId));
        addIfPresent(findings, slowMovers(storeId));
        addIfPresent(findings, marginAnomalies(storeId));
        return findings;
    }

    /** 1. 过期批次：只要有就是 HIGH，必须当天处理。 */
    private StewardDtos.Finding expiredBatches(Long storeId) {
        List<InventoryDtos.BatchView> expired = expiryService.expired(storeId);
        if (expired.isEmpty()) {
            return null;
        }
        // 先按压货金额排序再截断：报告留下的是"最贵的那几批"
        expired = expired.stream()
                .sorted(Comparator.comparing(this::batchAmount).reversed())
                .toList();
        BigDecimal amount = BigDecimal.ZERO;
        long quantity = 0;
        int omitted = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        StringBuilder detail = new StringBuilder("以下批次已过到期日，继续销售就是食品安全事故，请立即下架并在库存页做报损：");
        for (InventoryDtos.BatchView batch : expired) {
            quantity += batch.quantity() == null ? 0 : batch.quantity();
            BigDecimal lineAmount = batchAmount(batch);
            amount = amount.add(lineAmount);
            if (items.size() >= MAX_ITEMS_PER_FINDING) {
                omitted++;
                continue;
            }
            items.add(batchRow(batch, lineAmount));
            detail.append("\n· ").append(batch.productName()).append("（批次 ").append(batch.batchNo())
                    .append("）：到期 ").append(batch.expiryDate())
                    .append("，剩 ").append(batch.quantity()).append(" 件，压货 ").append(lineAmount).append(" 元");
        }
        appendOmitted(detail, omitted);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("batchCount", expired.size());
        metrics.put("quantity", quantity);
        metrics.put("costAmount", amount.setScale(2, RoundingMode.HALF_UP));
        metrics.put("items", items);
        metrics.put("omittedCount", omitted);
        return new StewardDtos.Finding("EXPIRED_BATCH", "HIGH",
                "有 " + expired.size() + " 个批次已过期（压货 " + amount.setScale(2, RoundingMode.HALF_UP) + " 元），必须立即下架报损",
                detail.toString(), metrics, List.of());
    }

    /** 2. 临期批次：7 天内到期算 HIGH，其余按预警阈值算 WARN。 */
    private StewardDtos.Finding expiringBatches(Long storeId) {
        int alertDays = expiryService.alertDays(null);
        List<InventoryDtos.BatchView> expiring = expiryService.expiring(storeId, null);
        if (expiring.isEmpty()) {
            return null;
        }
        expiring = expiring.stream()
                .sorted(Comparator.comparing(this::batchAmount).reversed())
                .toList();
        boolean urgent = expiring.stream().anyMatch(b -> b.daysToExpiry() != null && b.daysToExpiry() <= 7);
        BigDecimal amount = BigDecimal.ZERO;
        long quantity = 0;
        int omitted = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        StringBuilder detail = new StringBuilder("以下批次将在 ").append(alertDays).append(" 天内到期，建议优先售卖或促销：");
        for (InventoryDtos.BatchView batch : expiring) {
            quantity += batch.quantity() == null ? 0 : batch.quantity();
            BigDecimal lineAmount = batchAmount(batch);
            amount = amount.add(lineAmount);
            if (items.size() >= MAX_ITEMS_PER_FINDING) {
                omitted++;
                continue;
            }
            items.add(batchRow(batch, lineAmount));
            detail.append("\n· ").append(batch.productName()).append("（批次 ").append(batch.batchNo())
                    .append("）：剩 ").append(batch.daysToExpiry()).append(" 天到期（").append(batch.expiryDate())
                    .append("），数量 ").append(batch.quantity()).append("，压货 ").append(lineAmount).append(" 元");
        }
        appendOmitted(detail, omitted);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("alertDays", alertDays);
        metrics.put("batchCount", expiring.size());
        metrics.put("quantity", quantity);
        metrics.put("costAmount", amount.setScale(2, RoundingMode.HALF_UP));
        metrics.put("items", items);
        metrics.put("omittedCount", omitted);
        return new StewardDtos.Finding("EXPIRING_BATCH", urgent ? "HIGH" : "WARN",
                "有 " + expiring.size() + " 个批次临期（" + alertDays + " 天内到期，压货 "
                        + amount.setScale(2, RoundingMode.HALF_UP) + " 元）",
                detail.toString(), metrics, List.of());
    }

    /** 3. 断货风险：按近 7 天销速，剩余可卖天数不足 {@link #STOCKOUT_DAYS} 天。 */
    private StewardDtos.Finding stockoutRisks(Long storeId) {
        LocalDate today = LocalDate.now();
        Map<Long, Long> soldByProduct = new LinkedHashMap<>();
        for (ReportDtos.TopProduct product : reportService.topProducts(storeId,
                today.minusDays(StewardAnalysisService.SALES_WINDOW_DAYS - 1L), today, 500)) {
            soldByProduct.put(product.productId(), product.quantity());
        }
        List<Map<String, Object>> all = new ArrayList<>();
        for (InventoryDtos.LowStockItem item : inventoryService.lowStockItems(storeId)) {
            long sold = soldByProduct.getOrDefault(item.productId(), 0L);
            if (sold <= 0) {
                continue;
            }
            BigDecimal dailySales = BigDecimal.valueOf(sold)
                    .divide(BigDecimal.valueOf(StewardAnalysisService.SALES_WINDOW_DAYS), 2, RoundingMode.HALF_UP);
            BigDecimal daysLeft = BigDecimal.valueOf(item.stock()).divide(dailySales, 1, RoundingMode.HALF_UP);
            if (daysLeft.compareTo(BigDecimal.valueOf(STOCKOUT_DAYS)) >= 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", item.productId());
            row.put("productName", item.name());
            row.put("stock", item.stock());
            row.put("soldIn7d", sold);
            row.put("dailySales", dailySales);
            row.put("daysOfCoverLeft", daysLeft);
            all.add(row);
        }
        if (all.isEmpty()) {
            return null;
        }
        // 先按"还能撑几天"升序排（最先断货的排最前），再截断
        all.sort(Comparator.comparing(row -> (BigDecimal) row.get("daysOfCoverLeft")));
        List<Map<String, Object>> risky = all.size() > MAX_ITEMS_PER_FINDING
                ? new ArrayList<>(all.subList(0, MAX_ITEMS_PER_FINDING)) : all;
        int omitted = all.size() - risky.size();
        StringBuilder detail = new StringBuilder("按近 ").append(StewardAnalysisService.SALES_WINDOW_DAYS)
                .append(" 天的销速估算，以下商品很快会卖断：");
        for (Map<String, Object> row : risky) {
            detail.append("\n· ").append(row.get("productName")).append("：库存 ").append(row.get("stock"))
                    .append("，日均卖 ").append(row.get("dailySales")).append(" 件，只够卖 ")
                    .append(row.get("daysOfCoverLeft")).append(" 天");
        }
        appendOmitted(detail, omitted);
        detail.append("\n建议优先补这些商品（可在下方一键生成采购单草稿）。");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("count", all.size());
        metrics.put("items", risky);
        metrics.put("omittedCount", omitted);
        return new StewardDtos.Finding("STOCKOUT_RISK", "HIGH",
                "有 " + all.size() + " 个商品存在断货风险（按当前销速不足 " + STOCKOUT_DAYS + " 天）",
                detail.toString(), metrics, List.of());
    }

    /** 4. 补货建议：可一键转采购单草稿（这是巡检唯一的写动作入口，且仍是草稿）。 */
    private StewardDtos.Finding reorder(Long storeId) {
        List<StewardAnalysisService.ReorderSuggestion> suggestions = analysisService.reorderSuggestions(storeId, null);
        if (suggestions.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        int omitted = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        StringBuilder detail = new StringBuilder("按近 ").append(StewardAnalysisService.SALES_WINDOW_DAYS)
                .append(" 天销量建议补货（按进价估算金额）：");
        for (StewardAnalysisService.ReorderSuggestion suggestion : suggestions) {
            total = total.add(suggestion.estimatedAmount());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", suggestion.productId());
            row.put("productName", suggestion.productName());
            row.put("stock", suggestion.stock());
            row.put("threshold", suggestion.threshold());
            row.put("soldIn7d", suggestion.soldInWindow());
            row.put("dailySales", suggestion.dailySales());
            row.put("suggestQuantity", suggestion.suggestQuantity());
            row.put("unitCost", suggestion.unitCost());
            row.put("estimatedAmount", suggestion.estimatedAmount());
            if (items.size() >= MAX_ITEMS_PER_FINDING) {
                // 明细做上限：一键转草稿只带前 N 项，避免生成一张 200 行的采购单（没人核对得完）
                omitted++;
                continue;
            }
            items.add(row);
            detail.append("\n· ").append(suggestion.productName())
                    .append("：库存 ").append(suggestion.stock())
                    .append("（阈值 ").append(suggestion.threshold()).append("），建议补 ")
                    .append(suggestion.suggestQuantity()).append(" 件，约 ")
                    .append(suggestion.estimatedAmount()).append(" 元");
        }
        appendOmitted(detail, omitted);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("count", suggestions.size());
        metrics.put("estimatedAmount", total.setScale(2, RoundingMode.HALF_UP));
        metrics.put("items", items);
        metrics.put("omittedCount", omitted);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        StewardDtos.Action action = new StewardDtos.Action("CREATE_PURCHASE_DRAFT", "一键生成采购单草稿", true,
                "只生成草稿，不会改库存；确认入库仍需你在采购页操作", payload);
        return new StewardDtos.Finding("REORDER", "WARN",
                "建议补货 " + suggestions.size() + " 个商品（预估 " + total.setScale(2, RoundingMode.HALF_UP) + " 元）",
                detail.toString(), metrics, List.of(action));
    }

    /** 5. 滞销商品。 */
    private StewardDtos.Finding slowMovers(Long storeId) {
        int days = properties.getInventory().getSlowMovingDays();
        List<StewardAnalysisService.SlowMover> movers = analysisService.slowMovers(storeId, days);
        if (movers.isEmpty()) {
            return null;
        }
        BigDecimal tiedUp = BigDecimal.ZERO;
        int omitted = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        StringBuilder detail = new StringBuilder("以下商品超过 ").append(days).append(" 天没有卖出去，占着货架与资金：");
        for (StewardAnalysisService.SlowMover mover : movers) {
            tiedUp = tiedUp.add(mover.stockValue());
            if (items.size() >= MAX_ITEMS_PER_FINDING) {
                omitted++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", mover.productId());
            row.put("productName", mover.productName());
            row.put("stock", mover.stock());
            row.put("stockValue", mover.stockValue());
            row.put("lastSaleDate", mover.lastSaleDate() == null ? null : mover.lastSaleDate().toString());
            row.put("daysSinceLastSale", mover.daysSinceLastSale());
            items.add(row);
            detail.append("\n· ").append(mover.productName()).append("：库存 ").append(mover.stock())
                    .append("，压货 ").append(mover.stockValue()).append(" 元");
            if (mover.daysSinceLastSale() < 0) {
                detail.append("，从未卖出过");
            } else {
                detail.append("，已 ").append(mover.daysSinceLastSale()).append(" 天没卖出");
            }
        }
        appendOmitted(detail, omitted);
        detail.append("\n建议：做组合促销清库存，并暂停这些商品的进货。");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("days", days);
        metrics.put("count", movers.size());
        metrics.put("tiedUpAmount", tiedUp.setScale(2, RoundingMode.HALF_UP));
        metrics.put("items", items);
        metrics.put("omittedCount", omitted);
        return new StewardDtos.Finding("SLOW_MOVER", "WARN",
                "有 " + movers.size() + " 个商品滞销（压货 " + tiedUp.setScale(2, RoundingMode.HALF_UP) + " 元）",
                detail.toString(), metrics, List.of());
    }

    /** 6. 毛利异常。 */
    private StewardDtos.Finding marginAnomalies(Long storeId) {
        BigDecimal floor = BigDecimal.valueOf(properties.getSteward().getMarginAlertPercent());
        List<StewardAnalysisService.MarginAnomaly> anomalies = analysisService.marginAnomalies(storeId, 30, floor);
        if (anomalies.isEmpty()) {
            return null;
        }
        boolean losing = anomalies.stream().anyMatch(a -> a.grossProfit().compareTo(BigDecimal.ZERO) < 0);
        int omitted = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        StringBuilder detail = new StringBuilder("近 30 天毛利率低于 ").append(floor).append("% 的商品：");
        for (StewardAnalysisService.MarginAnomaly anomaly : anomalies) {
            if (items.size() >= MAX_ITEMS_PER_FINDING) {
                omitted++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", anomaly.productId());
            row.put("productName", anomaly.productName());
            row.put("soldQuantity", anomaly.soldQuantity());
            row.put("soldAmount", anomaly.soldAmount());
            row.put("grossProfit", anomaly.grossProfit());
            row.put("marginPercent", anomaly.marginPercent());
            items.add(row);
            detail.append("\n· ").append(anomaly.productName())
                    .append("：卖了 ").append(anomaly.soldAmount()).append(" 元，毛利 ")
                    .append(anomaly.grossProfit()).append(" 元（毛利率 ").append(anomaly.marginPercent()).append("%）");
        }
        appendOmitted(detail, omitted);
        detail.append(losing
                ? "\n注意：其中存在**负毛利**商品，通常是售价填错或进价上涨后没调价，请尽快核对定价。"
                : "\n建议：复核售价或与供应商谈进价，低毛利商品不要做折扣主力。");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("thresholdPercent", floor);
        metrics.put("count", anomalies.size());
        metrics.put("items", items);
        metrics.put("omittedCount", omitted);
        return new StewardDtos.Finding("MARGIN_ANOMALY", "WARN",
                "有 " + anomalies.size() + " 个商品毛利率低于 " + floor + "%" + (losing ? "（含负毛利）" : ""),
                detail.toString(), metrics, List.of());
    }

    /** 7. 账实不符：销售数量 vs 库存出库数量。 */
    private StewardDtos.Finding reconcileDiff(Long storeId) {
        ReportDtos.Reconcile reconcile = reportService.reconcile(storeId, LocalDate.now());
        if (reconcile.consistent()) {
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int omitted = 0;
        StringBuilder detail = new StringBuilder("以下商品的销售数量与库存出库数量对不上（可能是有人绕开收银台改了库存，或漏记出库）：");
        for (ReportDtos.ReconcileRow row : reconcile.diffs()) {
            if (items.size() >= MAX_ITEMS_PER_FINDING) {
                omitted++;
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", row.productId());
            item.put("productName", row.productName());
            item.put("soldQuantity", row.soldQuantity());
            item.put("flowQuantity", row.flowQuantity());
            item.put("diff", row.diff());
            items.add(item);
            detail.append("\n· ").append(row.productName()).append("：销售 ").append(row.soldQuantity())
                    .append(" 件，库存出库 ").append(row.flowQuantity()).append(" 件，差异 ").append(row.diff());
        }
        appendOmitted(detail, omitted);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("date", reconcile.date().toString());
        metrics.put("checkedProducts", reconcile.checkedProducts());
        metrics.put("count", reconcile.diffs().size());
        metrics.put("items", items);
        metrics.put("omittedCount", omitted);
        return new StewardDtos.Finding("RECONCILE_DIFF", "HIGH",
                "账实不符：" + reconcile.diffs().size() + " 个商品销售数量与出库数量不一致",
                detail.toString(), metrics, List.of());
    }

    /** 8. 批次与聚合库存不符：批次台账自身的一致性检查。 */
    private StewardDtos.Finding batchMismatch(Long storeId) {
        List<Map<String, Object>> mismatches = inventoryService.batchMismatches(storeId);
        if (mismatches.isEmpty()) {
            return null;
        }
        StringBuilder detail = new StringBuilder("以下商品的批次数量之和与库存总数不一致，需要盘点修正（否则临期预警与成本核算都会失真）：");
        for (Map<String, Object> row : mismatches) {
            detail.append("\n· ").append(row);
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("count", mismatches.size());
        metrics.put("items", mismatches);
        return new StewardDtos.Finding("BATCH_MISMATCH", "HIGH",
                "有 " + mismatches.size() + " 个商品的批次数量与库存总数对不上",
                detail.toString(), metrics, List.of());
    }

    // ------------------------------------------------------------------ 工具方法

    /** 批次压货金额（数量 × 批次成本价）。 */
    private BigDecimal batchAmount(InventoryDtos.BatchView batch) {
        BigDecimal cost = batch.costPrice() == null ? BigDecimal.ZERO : batch.costPrice();
        return cost.multiply(BigDecimal.valueOf(batch.quantity() == null ? 0 : batch.quantity()));
    }

    private Map<String, Object> batchRow(InventoryDtos.BatchView batch, BigDecimal lineAmount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productId", batch.productId());
        row.put("productName", batch.productName());
        row.put("batchNo", batch.batchNo());
        row.put("expiryDate", batch.expiryDate() == null ? null : batch.expiryDate().toString());
        row.put("daysToExpiry", batch.daysToExpiry());
        row.put("quantity", batch.quantity());
        row.put("costAmount", lineAmount.setScale(2, RoundingMode.HALF_UP));
        return row;
    }

    private void addIfPresent(List<StewardDtos.Finding> findings, StewardDtos.Finding finding) {
        if (finding != null) {
            findings.add(finding);
        }
    }

    /** 明细被上限截断时，在正文里说清"还有多少条、去哪看"——不能让人以为这就是全部。 */
    private void appendOmitted(StringBuilder detail, int omitted) {
        if (omitted > 0) {
            detail.append("\n… 另有 ").append(omitted).append(" 条同类明细未在本页列出（报告只保留前 ")
                    .append(MAX_ITEMS_PER_FINDING).append(" 条，完整清单请到对应页面查看）");
        }
    }

    /** 一句话总结：让店长在列表页/消息里一眼看到"今天要不要管"。 */
    private String headline(List<StewardDtos.Finding> findings) {
        if (findings.isEmpty()) {
            return "巡检完成：没有发现异常，库存、保质期、账目都对得上。";
        }
        long high = findings.stream().filter(f -> "HIGH".equals(f.severity())).count();
        StringBuilder sb = new StringBuilder("巡检完成：").append(findings.size()).append(" 项发现");
        if (high > 0) {
            sb.append("，其中 ").append(high).append(" 项需立即处理");
        }
        sb.append("（");
        List<String> parts = new ArrayList<>();
        for (StewardDtos.Finding finding : findings) {
            parts.add(CODE_LABELS.getOrDefault(finding.code(), finding.code()));
        }
        sb.append(String.join("、", parts)).append("）");
        return sb.toString();
    }

    private StewardDtos.ReportView toView(StewardReport report) {
        return new StewardDtos.ReportView(report.getId(), report.getReportDate(), report.getSource(),
                report.getGeneratedAt(), report.getHeadline(),
                report.getFindingCount() == null ? 0 : report.getFindingCount(),
                report.getHighCount() == null ? 0 : report.getHighCount(),
                fromJson(report.getFindings()));
    }

    private String toJson(List<StewardDtos.Finding> findings) {
        try {
            String json = objectMapper.writeValueAsString(findings);
            if (json.length() <= MAX_FINDINGS_JSON_CHARS) {
                return json;
            }
            // 兜底：极端数据（几百条明细）下宁可把明细剥掉，也不能让定时任务因为一次写入失败而整轮作废
            log.warn("巡检结果过大（{} 字符），降级为不含明细的摘要后落库", json.length());
            List<StewardDtos.Finding> slim = findings.stream()
                    .map(f -> new StewardDtos.Finding(f.code(), f.severity(), f.title(),
                            f.detail(), Map.of(), f.actions()))
                    .toList();
            return objectMapper.writeValueAsString(slim);
        } catch (Exception e) {
            log.warn("巡检结果序列化失败：{}", e.getMessage());
            return "[]";
        }
    }

    private List<StewardDtos.Finding> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<StewardDtos.Finding>>() {
            });
        } catch (Exception e) {
            log.warn("巡检结果反序列化失败：{}", e.getMessage());
            return List.of();
        }
    }
}
