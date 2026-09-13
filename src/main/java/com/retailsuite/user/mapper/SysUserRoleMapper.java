package com.retailsuite.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.user.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    @Select("SELECT COUNT(1) FROM sys_user_role WHERE user_id = #{userId} AND role_id = #{roleId}")
    long countByUserAndRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
