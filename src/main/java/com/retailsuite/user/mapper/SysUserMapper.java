package com.retailsuite.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.retailsuite.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 用户查询：角色与权限码用固定 SQL 取（三表/四表 join 不适合用 ORM 拼条件）。 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
            SELECT r.code FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.deleted = 0
            """)
    List<String> selectRoleCodes(@Param("userId") Long userId);

    @Select("""
            SELECT DISTINCT rp.permission_code FROM sys_role_permission rp
            JOIN sys_role r ON r.code = rp.role_code AND r.deleted = 0
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectPermissionCodes(@Param("userId") Long userId);
}
