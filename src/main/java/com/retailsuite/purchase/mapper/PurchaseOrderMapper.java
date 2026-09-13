package com.retailsuite.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.purchase.entity.PurchaseOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrder> {

    @Select("""
            SELECT * FROM purchase_order
             WHERE store_id = #{storeId} AND deleted = 0
             ORDER BY id DESC LIMIT #{limit}
            """)
    List<PurchaseOrder> recentByStore(@Param("storeId") Long storeId, @Param("limit") int limit);

    /** 状态机流转用条件更新：只有处于期望状态的行才会被更新，天然防并发重复确认。 */
    @Update("""
            UPDATE purchase_order
               SET status = #{toStatus}, confirmed_at = #{confirmedAt}, updated_at = #{now}
             WHERE id = #{id} AND store_id = #{storeId} AND deleted = 0 AND status = #{fromStatus}
            """)
    int transition(@Param("storeId") Long storeId,
                   @Param("id") Long id,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus,
                   @Param("confirmedAt") LocalDateTime confirmedAt,
                   @Param("now") LocalDateTime now);
}
