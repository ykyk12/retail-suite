package com.retailsuite.report.service;

import com.retailsuite.audit.AuditService;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.entity.DailySalesSummary;
import com.retailsuite.report.mapper.DailySalesSummaryMapper;
import com.retailsuite.report.mapper.ReportMapper;
import com.retailsuite.store.entity.Store;
import com.retailsuite.store.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表服务：实时概览 + 日汇总物化 + TOP 商品 + 对账差异 + 导出数据。
 *
 * 两种口径说清楚（面试常问"你的报表为什么快"）：
 * - **实时口径**（{@link #overview}）：直接聚合当天的订单与明细，永远最新，用于首页看板；
 * - **汇总口径**（{@link #dailySummaries}）：读 day 汇总表，用于趋势图与导出，数据量大时也不会慢，
 *   代价是最新一天要等定时任务（默认每天 00:10）刷新，所以接口返回的是"汇总时间点"的数值。
 * 毛利口径：用销售明细里**冻结的成本价**计算，公式为
 *   毛利 = (销售额 − 已售成本) − (退款额 − 已退成本)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportMapper reportMapper;
    private final DailySalesSummaryMapper summaryMapper;
    private final StoreMapper storeMapper;
    private final AuditService auditService;

    /** 实时概览（指定日期，默认今天）。 */
    public ReportDtos.Overview overview(Long storeId, LocalDate date) {
        LocalDate target = date == null ? LocalDate.now() : date;
        LocalDateTime from = target.atStartOfDay();
        LocalDateTime to = from.plusDays(1);

        ReportDtos.AggregateRow orders = reportMapper.aggregateOrders(storeId, from, to);
        ReportDtos.AggregateRow items = reportMapper.aggregateItems(storeId, from, to);

        long orderCount = number(orders == null ? null : orders.getOrderCount());
        long itemCount = number(items == null ? null : items.getItemCount());
        BigDecimal sales = money(items == null ? null : items.getSalesAmount());
        BigDecimal cost = money(items == null ? null : items.getCostAmount());
        BigDecimal refundedCost = money(items == null ? null : items.getRefundedCost());
        BigDecimal refund = money(orders == null ? null : orders.getRefundAmount());

        BigDecimal net = sales.subtract(refund).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grossProfit = sales.subtract(cost)
                .subtract(refund.subtract(refundedCost))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal avgOrder = orderCount == 0 ? BigDecimal.ZERO
                : net.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP);

        return new ReportDtos.Overview(target, orderCount, itemCount, sales, refund, net, grossProfit, avgOrder);
    }

    /** 重算某天汇总并落库（幂等：先查再插/改，同一天不会出现两行）。 */
    @Transactional(rollbackFor = Exception.class)
    public ReportDtos.DailyRow rebuildDailySummary(Long storeId, LocalDate date) {
        ReportDtos.Overview overview = overview(storeId, date);
        DailySalesSummary existing = summaryMapper.findByStoreAndDate(storeId, date);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            DailySalesSummary row = new DailySalesSummary();
            row.setStoreId(storeId);
            row.setSummaryDate(date);
            applyOverview(row, overview);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            summaryMapper.insert(row);
        } else {
            DailySalesSummary update = new DailySalesSummary();
            update.setId(existing.getId());
            applyOverview(update, overview);
            update.setUpdatedAt(now);
            summaryMapper.updateById(update);
        }
        return new ReportDtos.DailyRow(date, overview.orderCount(), overview.itemCount(),
                overview.salesAmount(), overview.refundAmount(), overview.netAmount(), overview.grossProfit());
    }

    /** 重算一个日期区间（导出前或修数后使用）。 */
    public int rebuildRange(Long storeId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("日期区间不合法：from=" + from + " to=" + to);
        }
        int days = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            rebuildDailySummary(storeId, day);
            days++;
        }
        auditService.record("REPORT_REBUILD", "daily_summary", from + "~" + to, "重算汇总 " + days + " 天");
        log.info("汇总重算完成 storeId={} {} ~ {} 共 {} 天", storeId, from, to, days);
        return days;
    }

    /** 所有门店都重算（定时任务用）。 */
    public int rebuildAllStores(LocalDate date) {
        int count = 0;
        for (Store store : storeMapper.selectList(null)) {
            rebuildDailySummary(store.getId(), date);
            count++;
        }
        return count;
    }

    /** 汇总口径的日报（读汇总表）。 */
    public List<ReportDtos.DailyRow> dailySummaries(Long storeId, LocalDate from, LocalDate to) {
        List<DailySalesSummary> rows = summaryMapper.listByRange(storeId, from, to);
        List<ReportDtos.DailyRow> views = new ArrayList<>(rows.size());
        for (DailySalesSummary row : rows) {
            views.add(new ReportDtos.DailyRow(row.getSummaryDate(), numberInt(row.getOrderCount()),
                    numberInt(row.getItemCount()), money(row.getSalesAmount()),
                    money(row.getRefundAmount()), money(row.getNetAmount()), money(row.getGrossProfit())));
        }
        return views;
    }

    public List<ReportDtos.TopProduct> topProducts(Long storeId, LocalDate from, LocalDate to, int limit) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<ReportDtos.TopProductRow> rows = reportMapper.topProducts(storeId, start, end,
                Math.min(Math.max(limit, 1), 100));
        List<ReportDtos.TopProduct> views = new ArrayList<>(rows.size());
        for (ReportDtos.TopProductRow row : rows) {
            BigDecimal amount = money(row.getAmount());
            BigDecimal cost = money(row.getCost());
            views.add(new ReportDtos.TopProduct(row.getProductId(), row.getProductName(),
                    number(row.getQuantity()), amount, amount.subtract(cost).setScale(2, RoundingMode.HALF_UP)));
        }
        return views;
    }

    /**
     * 对账：某天"卖出去的数量"是否等于"库存扣减的数量"。
     * 两侧都由系统自己写，理论上必须一致；不一致就说明有绕过收银的库存改动或数据被人工改过——
     * 这是门店最关心的"账实相符"检查，也是把库存流水独立存表的价值所在。
     */
    public ReportDtos.Reconcile reconcile(Long storeId, LocalDate date) {
        LocalDate target = date == null ? LocalDate.now() : date;
        LocalDateTime from = target.atStartOfDay();
        LocalDateTime to = from.plusDays(1);

        Map<Long, Long> sold = toMap(reportMapper.soldQuantityByProduct(storeId, from, to));
        Map<Long, Long> flows = toMap(reportMapper.saleFlowQuantityByProduct(storeId, from, to));

        List<ReportDtos.ReconcileRow> diffs = new ArrayList<>();
        long checked = 0;
        for (Long productId : union(sold, flows)) {
            long soldQty = sold.getOrDefault(productId, 0L);
            long flowQty = flows.getOrDefault(productId, 0L);
            checked++;
            if (soldQty != flowQty) {
                diffs.add(new ReportDtos.ReconcileRow(productId, reportMapper.productName(productId),
                        soldQty, flowQty, soldQty - flowQty));
            }
        }
        if (!diffs.isEmpty()) {
            log.warn("对账发现差异 storeId={} date={} 差异商品数={}", storeId, target, diffs.size());
        }
        return new ReportDtos.Reconcile(target, diffs.isEmpty(), checked, diffs);
    }

    /** 导出用的二维数据（表头 + 行）。 */
    public List<List<Object>> exportDailyRows(Long storeId, LocalDate from, LocalDate to) {
        List<ReportDtos.DailyRow> rows = dailySummaries(storeId, from, to);
        if (rows.isEmpty()) {
            // 汇总表还没跑过：退化为实时计算，避免用户导出到空文件
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                ReportDtos.Overview overview = overview(storeId, day);
                rows.add(new ReportDtos.DailyRow(day, overview.orderCount(), overview.itemCount(),
                        overview.salesAmount(), overview.refundAmount(), overview.netAmount(),
                        overview.grossProfit()));
            }
        }
        List<List<Object>> data = new ArrayList<>(rows.size());
        for (ReportDtos.DailyRow row : rows) {
            data.add(List.of(row.date().toString(), row.orderCount(), row.itemCount(),
                    row.salesAmount().toPlainString(), row.refundAmount().toPlainString(),
                    row.netAmount().toPlainString(), row.grossProfit().toPlainString()));
        }
        return data;
    }

    private void applyOverview(DailySalesSummary row, ReportDtos.Overview overview) {
        row.setOrderCount((int) overview.orderCount());
        row.setItemCount((int) overview.itemCount());
        row.setSalesAmount(overview.salesAmount());
        row.setRefundAmount(overview.refundAmount());
        row.setNetAmount(overview.netAmount());
        row.setGrossProfit(overview.grossProfit());
    }

    private Map<Long, Long> toMap(List<ReportDtos.ProductQuantityRow> rows) {
        Map<Long, Long> map = new LinkedHashMap<>();
        for (ReportDtos.ProductQuantityRow row : rows) {
            map.put(row.getProductId(), number(row.getQuantity()));
        }
        return map;
    }

    private List<Long> union(Map<Long, Long> left, Map<Long, Long> right) {
        Map<Long, Long> merged = new LinkedHashMap<>();
        left.keySet().forEach(id -> merged.put(id, 0L));
        right.keySet().forEach(id -> merged.put(id, 0L));
        return new ArrayList<>(merged.keySet());
    }

    private long number(Long value) {
        return value == null ? 0L : value;
    }

    private long numberInt(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }
}
