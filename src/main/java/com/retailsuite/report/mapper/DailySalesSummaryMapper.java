package com.retailsuite.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.report.entity.DailySalesSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DailySalesSummaryMapper extends BaseMapper<DailySalesSummary> {

    @Select("""
            SELECT * FROM daily_sales_summary
             WHERE store_id = #{storeId} AND summary_date = #{date}
            """)
    DailySalesSummary findByStoreAndDate(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    @Select("""
            SELECT * FROM daily_sales_summary
             WHERE store_id = #{storeId} AND summary_date >= #{from} AND summary_date <= #{to}
             ORDER BY summary_date
            """)
    List<DailySalesSummary> listByRange(@Param("storeId") Long storeId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);
}
