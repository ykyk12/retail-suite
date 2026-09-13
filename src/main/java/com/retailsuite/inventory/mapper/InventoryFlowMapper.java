package com.retailsuite.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.inventory.entity.InventoryFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface InventoryFlowMapper extends BaseMapper<InventoryFlow> {

    @Select("""
            SELECT * FROM inventory_flow
             WHERE store_id = #{storeId} AND product_id = #{productId} AND deleted = 0
             ORDER BY id DESC LIMIT #{limit}
            """)
    List<InventoryFlow> recentByProduct(@Param("storeId") Long storeId,
                                       @Param("productId") Long productId,
                                       @Param("limit") int limit);

    @Select("""
            SELECT * FROM inventory_flow
             WHERE store_id = #{storeId} AND deleted = 0 AND created_at >= #{from} AND created_at < #{to}
             ORDER BY id DESC
            """)
    List<InventoryFlow> listByRange(@Param("storeId") Long storeId,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);
}
