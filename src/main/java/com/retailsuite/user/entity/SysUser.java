package com.retailsuite.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户（员工）。密码只存 BCrypt 哈希，任何接口都不返回该字段。 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属门店：所有业务数据的隔离维度 */
    private Long storeId;

    private String username;

    /** BCrypt 哈希。视图对象里绝不出现这个字段。 */
    private String passwordHash;

    private String realName;

    private String phone;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
