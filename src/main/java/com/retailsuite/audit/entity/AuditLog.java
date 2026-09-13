package com.retailsuite.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（append-only）：谁、什么时候、对什么、做了什么。
 * 刻意没有 deleted 字段——审计记录不允许被业务代码删除，只能由归档任务按时间清理。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeId;

    private Long userId;

    private String username;

    private String action;

    private String targetType;

    private String targetId;

    private String detail;

    private String ip;

    private LocalDateTime createdAt;
}
