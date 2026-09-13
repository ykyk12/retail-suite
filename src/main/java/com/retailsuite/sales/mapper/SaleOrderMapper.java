package com.retailsuite.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.sales.entity.SaleOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SaleOrderMapper extends BaseMapper<SaleOrder> {

    @Select("SELECT * FROM sale_order WHERE request_id = #{requestId} AND deleted = 0")
    SaleOrder selectByRequestId(@Param("requestId") String requestId);

    @Select("""
            SELECT * FROM sale_order
             WHERE store_id = #{storeId} AND deleted = 0 AND created_at >= #{from} AND created_at < #{to}
             ORDER BY id DESC
            """)
    List<SaleOrder> listByRange(@Param("storeId") Long storeId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

    @Update("""
            UPDATE sale_order
               SET refund_amount = refund_amount + #{delta},
                   status = #{status},
                   updated_at = #{now}
             WHERE id = #{orderId} AND store_id = #{storeId} AND deleted = 0
            """)
    int applyRefund(@Param("storeId") Long storeId,
                    @Param("orderId") Long orderId,
                    @Param("delta") BigDecimal delta,
                    @Param("status") String status,
                    @Param("now") LocalDateTime now);
}
