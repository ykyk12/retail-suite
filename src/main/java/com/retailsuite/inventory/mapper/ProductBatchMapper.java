package com.retailsuite.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.inventory.entity.ProductBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 批次相关 SQL。
 *
 * FIFO（这里实现为"近效期先出"）的关键在 ORDER BY：
 *   `ORDER BY expiry_date IS NULL, expiry_date ASC, id ASC`
 * 用 `expiry_date IS NULL` 把无到期日的批次排到最后（日用品等），
 * 这样既保证了临期优先出，又不会因为 NULL 排序在不同数据库下表现不同而出错
 * （MySQL 与 H2 对 NULL 在 ORDER BY 中的默认位置不一致，显式写出来最稳）。
 */
@Mapper
public interface ProductBatchMapper extends BaseMapper<ProductBatch> {

    /** 可出库批次，近效期优先。 */
    @Select("""
            SELECT * FROM product_batch
             WHERE store_id = #{storeId} AND product_id = #{productId} AND deleted = 0 AND quantity > 0
             ORDER BY expiry_date IS NULL, expiry_date ASC, id ASC
            """)
    List<ProductBatch> selectForIssue(@Param("storeId") Long storeId, @Param("productId") Long productId);

    /** 扣减批次数量（条件更新：不足则影响行数为 0，天然防并发扣成负数）。 */
    @Update("""
            UPDATE product_batch
               SET quantity = quantity - #{quantity}, updated_at = #{now}
             WHERE id = #{batchId} AND deleted = 0 AND quantity >= #{quantity}
            """)
    int consume(@Param("batchId") Long batchId, @Param("quantity") int quantity, @Param("now") LocalDateTime now);

    /** 某商品的全部未删除批次（含已过期/已售罄，用于追溯）。 */
    @Select("""
            SELECT * FROM product_batch
             WHERE store_id = #{storeId} AND product_id = #{productId} AND deleted = 0
             ORDER BY expiry_date IS NULL, expiry_date ASC, id DESC
            """)
    List<ProductBatch> listByProduct(@Param("storeId") Long storeId, @Param("productId") Long productId);

    /** 临期批次：有到期日、未过期、剩余数量 > 0、剩余天数 ≤ days。 */
    @Select("""
            SELECT * FROM product_batch
             WHERE store_id = #{storeId} AND deleted = 0 AND quantity > 0
               AND expiry_date IS NOT NULL AND expiry_date >= #{today}
               AND expiry_date <= #{deadline}
             ORDER BY expiry_date ASC
            """)
    List<ProductBatch> selectExpiring(@Param("storeId") Long storeId,
                                      @Param("today") LocalDate today,
                                      @Param("deadline") LocalDate deadline);

    /** 已过期批次（剩余数量 > 0，需要报损或下架）。 */
    @Select("""
            SELECT * FROM product_batch
             WHERE store_id = #{storeId} AND deleted = 0 AND quantity > 0
               AND expiry_date IS NOT NULL AND expiry_date < #{today}
             ORDER BY expiry_date ASC
            """)
    List<ProductBatch> selectExpired(@Param("storeId") Long storeId, @Param("today") LocalDate today);

    /** 各商品批次数量合计（对账用：必须与 product.stock 相等）。 */
    @Select("""
            SELECT product_id, COALESCE(SUM(quantity), 0) AS quantity
              FROM product_batch
             WHERE store_id = #{storeId} AND deleted = 0
             GROUP BY product_id
            """)
    List<Map<String, Object>> sumQuantityGroupByProduct(@Param("storeId") Long storeId);
}
