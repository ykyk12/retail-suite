package com.retailsuite.agent.runtime;

import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.audit.AuditService;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.config.AppProperties;
import com.retailsuite.security.AuthUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管家 Agent 的守卫：**权限、限流、审计**三件事集中在这里，工具实现里不用各写一遍。
 *
 * 为什么企业级必须有它：
 * - 权限：模型可能"猜"出一个工具名（比如被提示注入诱导去调用进货接口），所以每次调用都要再校验一次权限码，
 *   不能只靠"提示词里没列出来"；
 * - 限流：Agent 一次对话可能连调多个工具，没有上限就是一个成本与稳定性黑洞；这里按"用户/分钟"计数；
 * - 审计：谁在什么时候、通过哪次对话、调了什么工具、传了什么参数、结果如何——事后必须能查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGuard {

    private final AppProperties properties;
    private final AuditService auditService;

    /** 用户 → 当前分钟窗口的工具调用计数 */
    private final Map<Long, Counter> counters = new ConcurrentHashMap<>();

    private record Counter(LocalDateTime windowStart, AtomicInteger count) {
    }

    /** 权限校验：没权限直接拒绝（并且把"是谁想调什么"记进审计，便于发现异常尝试）。 */
    public void checkPermission(AuthUser user, AgentTool tool) {
        if (tool.permission() == null || tool.permission().isBlank()) {
            return;
        }
        if (user == null || !user.hasPermission(tool.permission())) {
            auditService.record("AGENT_TOOL_DENIED", "agent_tool", tool.name(),
                    "缺少权限 " + tool.permission() + "，user=" + (user == null ? "anonymous" : user.username()));
            throw new BizException(ErrorCode.FORBIDDEN,
                    "当前账号没有权限使用工具 " + tool.name() + "（需要 " + tool.permission() + "）");
        }
    }

    /** 每用户每分钟的工具调用上限。 */
    public void checkRateLimit(AuthUser user) {
        if (user == null) {
            return;
        }
        int limit = properties.getAi().getAgentMaxToolCallsPerMinute();
        if (limit <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        Counter counter = counters.compute(user.userId(), (userId, existing) ->
                existing == null || !existing.windowStart().equals(now)
                        ? new Counter(now, new AtomicInteger()) : existing);
        int used = counter.count().incrementAndGet();
        if (used > limit) {
            auditService.record("AGENT_RATE_LIMITED", "agent_tool", null,
                    "超过每分钟工具调用上限 " + limit + "，user=" + user.username());
            throw new BizException(ErrorCode.CONFLICT,
                    "操作过于频繁：每分钟最多 " + limit + " 次工具调用，请稍后再试");
        }
    }

    /** 记录一次工具调用（入参、耗时、结果摘要）。 */
    public void audit(AuthUser user, AgentTool tool, Map<String, Object> args,
                      ToolOutcome outcome, long elapsedMillis) {
        String detail = "tool=" + tool.name()
                + " args=" + args
                + " elapsed=" + elapsedMillis + "ms"
                + " ok=" + outcome.success()
                + " observation=" + abbreviate(outcome.summary());
        auditService.record("AGENT_TOOL_CALL", "agent_tool", tool.name(), detail);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
