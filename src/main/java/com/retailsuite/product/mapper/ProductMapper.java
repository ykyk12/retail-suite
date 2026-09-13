package com.retailsuite.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 商品与库存的 SQL 出口。
 *
 * 这里刻意手写"条件更新"而不是先查再改：
 *   UPDATE product SET stock = stock - #{quantity} WHERE id = ? AND stock >= #{quantity}
 * 数据库把"判断够不够"和"扣减"放在一条语句里完成（行锁内原子），返回影响行数 0 就说明库存不足。
 * 这样在高并发（同一商品多人同时扫码）下**不可能超卖**，也不需要悲观锁或分布式锁。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /** 扣减库存：库存不足时返回 0（调用方据此抛"库存不足"，并在同事务内读回真实库存用于提示）。 */
    @Update("""
            UPDATE product
               SET stock = stock - #{quantity}, version = version + 1, updated_at = #{now}
             WHERE id = #{productId} AND store_id = #{storeId} AND deleted = 0 AND stock >= #{quantity}
            """)
    int decreaseStock(@Param("storeId") Long storeId,
                      @Param("productId") Long productId,
                      @Param("quantity") int quantity,
                      @Param("now") LocalDateTime now);

    /** 增加库存（采购入库、退货回补）。 */
    @Update("""
            UPDATE product
               SET stock = stock + #{quantity}, version = version + 1, updated_at = #{now}
             WHERE id = #{productId} AND store_id = #{storeId} AND deleted = 0
            """)
    int increaseStock(@Param("storeId") Long storeId,
                      @Param("productId") Long productId,
                      @Param("quantity") int quantity,
                      @Param("now") LocalDateTime now);

    /** 直接设置库存（盘点/调整用），同一事务写流水，所以这里允许绝对赋值。 */
    @Update("""
            UPDATE product
               SET stock = #{stock}, version = version + 1, updated_at = #{now}
             WHERE id = #{productId} AND store_id = #{storeId} AND deleted = 0
            """)
    int resetStock(@Param("storeId") Long storeId,
                   @Param("productId") Long productId,
                   @Param("stock") int stock,
                   @Param("now") LocalDateTime now);
}
