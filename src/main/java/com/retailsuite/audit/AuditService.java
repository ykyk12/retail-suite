package com.retailsuite.audit;

import com.retailsuite.audit.entity.AuditLog;
import com.retailsuite.audit.mapper.AuditLogMapper;
import com.retailsuite.security.AuthUser;
import com.retailsuite.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 审计服务：关键写操作（登录、入库、收银、退货、库存调整、AI 草稿确认）都要留痕。
 *
 * 两条刻意的设计：
 * 1) 审计失败**不能**影响主业务（收银不能因为写日志失败而失败），所以这里吞异常只告警；
 * 2) 审计写入与主业务在同一事务内会带来"主业务回滚、审计也消失"的问题；
 *    本项目选择与主业务同事务（简单、可保证一致），若将来审计要求"即使业务回滚也要留痕"，
 *    应改为独立事务/消息异步落库——这是个明确的取舍点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public void record(String action, String targetType, String targetId, String detail) {
        try {
            AuthUser user = UserContext.getOrNull();
            AuditLog logRow = new AuditLog();
            logRow.setStoreId(user == null || user.storeId() == null ? 0L : user.storeId());
            logRow.setUserId(user == null ? null : user.userId());
            logRow.setUsername(user == null ? "anonymous" : user.username());
            logRow.setAction(action);
            logRow.setTargetType(targetType);
            logRow.setTargetId(targetId == null ? null : truncate(targetId, 64));
            logRow.setDetail(truncate(detail, 500));
            logRow.setIp(currentIp());
            logRow.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(logRow);
        } catch (RuntimeException e) {
            log.warn("审计写入失败（不影响主业务）：action={} detail={} err={}", action, detail, e.toString());
        }
    }

    /** 登录失败这类场景没有登录上下文，需要显式指定门店与用户名。 */
    public void recordAnonymous(Long storeId, String username, String action, String detail) {
        try {
            AuditLog logRow = new AuditLog();
            logRow.setStoreId(storeId == null ? 0L : storeId);
            logRow.setUsername(username == null ? "anonymous" : username);
            logRow.setAction(action);
            logRow.setDetail(truncate(detail, 500));
            logRow.setIp(currentIp());
            logRow.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(logRow);
        } catch (RuntimeException e) {
            log.warn("审计写入失败（不影响主业务）：action={} err={}", action, e.toString());
        }
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return truncate(forwarded.split(",")[0].trim(), 64);
            }
            return truncate(request.getRemoteAddr(), 64);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
