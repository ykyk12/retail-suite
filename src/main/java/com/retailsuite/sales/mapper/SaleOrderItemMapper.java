package com.retailsuite.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.sales.entity.SaleOrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SaleOrderItemMapper extends BaseMapper<SaleOrderItem> {

    @Select("SELECT * FROM sale_order_item WHERE order_id = #{orderId} AND deleted = 0 ORDER BY id")
    List<SaleOrderItem> listByOrder(@Param("orderId") Long orderId);

    /**
     * 退货计数用条件更新：只有"未退数量 ≥ 本次退货数量"时才允许更新。
     * 这样并发两笔退货不会把这个商品退超（退超就等于凭空多出货、库存虚增）。
     */
    @Update("""
            UPDATE sale_order_item
               SET refunded_quantity = refunded_quantity + #{quantity}
             WHERE id = #{itemId} AND order_id = #{orderId} AND deleted = 0
               AND quantity - refunded_quantity >= #{quantity}
            """)
    int addRefundedQuantity(@Param("orderId") Long orderId,
                            @Param("itemId") Long itemId,
                            @Param("quantity") int quantity);
}
