package com.retailsuite.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.purchase.entity.PurchaseOrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PurchaseOrderItemMapper extends BaseMapper<PurchaseOrderItem> {

    @Select("SELECT * FROM purchase_order_item WHERE order_id = #{orderId} AND deleted = 0 ORDER BY id")
    List<PurchaseOrderItem> listByOrder(@Param("orderId") Long orderId);
}
