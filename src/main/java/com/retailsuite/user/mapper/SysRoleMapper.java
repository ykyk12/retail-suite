package com.retailsuite.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.user.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("SELECT id FROM sys_role WHERE code = #{code} AND deleted = 0")
    Long selectIdByCode(@Param("code") String code);
}
