package com.retailsuite.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.user.entity.SysRole;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("SELECT id FROM sys_role WHERE code = #{code} AND deleted = 0")
    Long selectIdByCode(@Param("code") String code);

    @Select("""
            SELECT COUNT(1) FROM sys_role_permission
             WHERE role_code = #{roleCode} AND permission_code = #{permissionCode}
            """)
    long countPermission(@Param("roleCode") String roleCode, @Param("permissionCode") String permissionCode);

    @Insert("""
            INSERT INTO sys_role_permission (role_code, permission_code)
            VALUES (#{roleCode}, #{permissionCode})
            """)
    int insertPermission(@Param("roleCode") String roleCode, @Param("permissionCode") String permissionCode);
}
