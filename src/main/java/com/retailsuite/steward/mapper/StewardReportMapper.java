package com.retailsuite.steward.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.steward.entity.StewardReport;
import org.apache.ibatis.annotations.Mapper;

/** 巡检日报 Mapper（查询条件用 LambdaQueryWrapper 在服务层拼，保持 SQL 集中可读）。 */
@Mapper
public interface StewardReportMapper extends BaseMapper<StewardReport> {
}
