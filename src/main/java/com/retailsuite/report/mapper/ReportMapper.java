package com.retailsuite.report.mapper;

import com.retailsuite.report.dto.ReportDtos;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报表聚合查询。
 *
 * 三条刻意的写法：
 * 1) **不依赖日期函数**：所有查询用 [from, to) 时间区间做过滤，按天由 Java 传入区间，
 *    避免 DATE()/DATE_FORMAT() 这类函数在 H2 与 MySQL 之间的方言差异；
 * 2) 毛利分开算：已售成本与已退成本都从销售明细里取（成本价在下单时已冻结），
 *    所以毛利 = (销售额 − 已售成本) − (退款额 − 已退成本)，而不是拿商品今天的进价去估算；
 * 3) 对账用两条独立聚合（销售数量 / 库存出库流水数量）再在 Java 里比对，
 *    比写一条复杂 FULL JOIN 更好读，出错时也更容易定位是哪一侧的问题。
 */
@Mapper
public interface ReportMapper {

    /** 订单维度聚合。 */
    @Select("""
            SELECT COUNT(*) AS order_count,
                   COALESCE(SUM(refund_amount), 0) AS refund_amount,
                   COALESCE(SUM(pay_amount), 0) AS pay_amount,
                   COALESCE(SUM(total_amount), 0) AS sales_amount
              FROM sale_order
             WHERE store_id = #{storeId} AND deleted = 0
               AND created_at >= #{from} AND created_at < #{to}
            """)
    ReportDtos.AggregateRow aggregateOrders(@Param("storeId") Long storeId,
                                            @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

    /** 明细维度聚合（营业额、成本、已退成本）。 */
    @Select("""
            SELECT COALESCE(SUM(soi.quantity), 0) AS item_count,
                   COALESCE(SUM(soi.amount), 0) AS sales_amount,
                   COALESCE(SUM(soi.cost_price * soi.quantity), 0) AS cost_amount,
                   COALESCE(SUM(soi.cost_price * soi.refunded_quantity), 0) AS refunded_cost
              FROM sale_order_item soi
              JOIN sale_order so ON so.id = soi.order_id
             WHERE so.store_id = #{storeId} AND so.deleted = 0 AND soi.deleted = 0
               AND so.created_at >= #{from} AND so.created_at < #{to}
            """)
    ReportDtos.AggregateRow aggregateItems(@Param("storeId") Long storeId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);

    /** TOP 商品（按销售额倒序）。 */
    @Select("""
            SELECT soi.product_id,
                   MIN(soi.product_name) AS product_name,
                   COALESCE(SUM(soi.quantity), 0) AS quantity,
                   COALESCE(SUM(soi.amount), 0) AS amount,
                   COALESCE(SUM(soi.cost_price * soi.quantity), 0) AS cost
              FROM sale_order_item soi
              JOIN sale_order so ON so.id = soi.order_id
             WHERE so.store_id = #{storeId} AND so.deleted = 0 AND soi.deleted = 0
               AND so.created_at >= #{from} AND so.created_at < #{to}
             GROUP BY soi.product_id
             ORDER BY amount DESC
             LIMIT #{limit}
            """)
    List<ReportDtos.TopProductRow> topProducts(@Param("storeId") Long storeId,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to,
                                               @Param("limit") int limit);

    /** 对账左侧：销售明细里的销售数量（按商品）。 */
    @Select("""
            SELECT soi.product_id, COALESCE(SUM(soi.quantity), 0) AS quantity
              FROM sale_order_item soi
              JOIN sale_order so ON so.id = soi.order_id
             WHERE so.store_id = #{storeId} AND so.deleted = 0 AND soi.deleted = 0
               AND so.created_at >= #{from} AND so.created_at < #{to}
             GROUP BY soi.product_id
            """)
    List<ReportDtos.ProductQuantityRow> soldQuantityByProduct(@Param("storeId") Long storeId,
                                                              @Param("from") LocalDateTime from,
                                                              @Param("to") LocalDateTime to);

    /** 对账右侧：库存流水里的销售出库数量（按商品，ref_type='SALE'，用负数取绝对值）。 */
    @Select("""
            SELECT product_id, COALESCE(SUM(-quantity), 0) AS quantity
              FROM inventory_flow
             WHERE store_id = #{storeId} AND deleted = 0 AND ref_type = 'SALE'
               AND created_at >= #{from} AND created_at < #{to}
             GROUP BY product_id
            """)
    List<ReportDtos.ProductQuantityRow> saleFlowQuantityByProduct(@Param("storeId") Long storeId,
                                                                  @Param("from") LocalDateTime from,
                                                                  @Param("to") LocalDateTime to);

    /** 商品名（对账结果展示用）。 */
    @Select("SELECT name FROM product WHERE id = #{productId}")
    String productName(@Param("productId") Long productId);

    /** 单个商品在区间内的销量/销售额/成本（管家"单品画像"用）。 */
    @Select("""
            SELECT COALESCE(SUM(soi.quantity), 0) AS item_count,
                   COALESCE(SUM(soi.amount), 0) AS sales_amount,
                   COALESCE(SUM(soi.cost_price * soi.quantity), 0) AS cost_amount
              FROM sale_order_item soi
              JOIN sale_order so ON so.id = soi.order_id
             WHERE so.store_id = #{storeId} AND so.deleted = 0 AND soi.deleted = 0
               AND soi.product_id = #{productId}
               AND so.created_at >= #{from} AND so.created_at < #{to}
            """)
    ReportDtos.AggregateRow salesOfProduct(@Param("storeId") Long storeId,
                                           @Param("productId") Long productId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);

    /** 某商品最后一次售出时间（判断"多久没卖出去了"用）。 */
    @Select("""
            SELECT MAX(so.created_at)
              FROM sale_order_item soi
              JOIN sale_order so ON so.id = soi.order_id
             WHERE so.store_id = #{storeId} AND so.deleted = 0 AND soi.deleted = 0
               AND soi.product_id = #{productId}
            """)
    LocalDateTime lastSaleAt(@Param("storeId") Long storeId, @Param("productId") Long productId);
}
