package com.retailsuite.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 报表 DTO。 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /** 经营概览（实时聚合，用于首页看板）。 */
    public record Overview(LocalDate date,
                           long orderCount,
                           long itemCount,
                           BigDecimal salesAmount,
                           BigDecimal refundAmount,
                           BigDecimal netAmount,
                           BigDecimal grossProfit,
                           BigDecimal avgOrderAmount) {
    }

    /** 日报行（来自汇总表，用于趋势图与导出）。 */
    public record DailyRow(LocalDate date,
                           long orderCount,
                           long itemCount,
                           BigDecimal salesAmount,
                           BigDecimal refundAmount,
                           BigDecimal netAmount,
                           BigDecimal grossProfit) {
    }

    /** TOP 商品。 */
    public record TopProduct(Long productId,
                             String productName,
                             long quantity,
                             BigDecimal amount,
                             BigDecimal grossProfit) {
    }

    /** 对账差异行：销售数量 vs 库存出库数量。 */
    public record ReconcileRow(Long productId,
                               String productName,
                               long soldQuantity,
                               long flowQuantity,
                               long diff) {
    }

    public record Reconcile(LocalDate date, boolean consistent, long checkedProducts, List<ReconcileRow> diffs) {
    }

    /** MyBatis 聚合结果载体（用 POJO 而不是 Map：列名 → 属性映射由配置的驼峰转换保证）。 */
    public static class AggregateRow {
        private Long orderCount;
        private Long itemCount;
        private BigDecimal salesAmount;
        private BigDecimal refundAmount;
        private BigDecimal payAmount;
        private BigDecimal costAmount;
        private BigDecimal refundedCost;

        public Long getOrderCount() {
            return orderCount;
        }

        public void setOrderCount(Long orderCount) {
            this.orderCount = orderCount;
        }

        public Long getItemCount() {
            return itemCount;
        }

        public void setItemCount(Long itemCount) {
            this.itemCount = itemCount;
        }

        public BigDecimal getSalesAmount() {
            return salesAmount;
        }

        public void setSalesAmount(BigDecimal salesAmount) {
            this.salesAmount = salesAmount;
        }

        public BigDecimal getRefundAmount() {
            return refundAmount;
        }

        public void setRefundAmount(BigDecimal refundAmount) {
            this.refundAmount = refundAmount;
        }

        public BigDecimal getPayAmount() {
            return payAmount;
        }

        public void setPayAmount(BigDecimal payAmount) {
            this.payAmount = payAmount;
        }

        public BigDecimal getCostAmount() {
            return costAmount;
        }

        public void setCostAmount(BigDecimal costAmount) {
            this.costAmount = costAmount;
        }

        public BigDecimal getRefundedCost() {
            return refundedCost;
        }

        public void setRefundedCost(BigDecimal refundedCost) {
            this.refundedCost = refundedCost;
        }
    }

    /** 商品维度的数量聚合行（对账用）。 */
    public static class ProductQuantityRow {
        private Long productId;
        private Long quantity;

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public Long getQuantity() {
            return quantity;
        }

        public void setQuantity(Long quantity) {
            this.quantity = quantity;
        }
    }

    /** TOP 商品聚合行。 */
    public static class TopProductRow {
        private Long productId;
        private String productName;
        private Long quantity;
        private BigDecimal amount;
        private BigDecimal cost;

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public Long getQuantity() {
            return quantity;
        }

        public void setQuantity(Long quantity) {
            this.quantity = quantity;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public BigDecimal getCost() {
            return cost;
        }

        public void setCost(BigDecimal cost) {
            this.cost = cost;
        }
    }
}
