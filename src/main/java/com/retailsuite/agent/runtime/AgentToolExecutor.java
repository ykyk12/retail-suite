package com.retailsuite.agent.runtime;

import com.retailsuite.agent.dto.AgentDtos;
import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.AgentToolRegistry;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.security.AuthUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工具执行器：把"守卫 → 参数校验 → 执行 → 审计"这条固定流程收在一处，
 * 模型路径与规则兜底路径共用同一套执行逻辑（避免两条路径行为不一致——那种 bug 最难查）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolExecutor {

    private static final int OBSERVATION_MAX_CHARS = 2000;

    private final AgentToolRegistry registry;
    private final AgentGuard guard;

    /** 一次工具调用的结果：轨迹（给人看/给前端展示）+ 原始结果（给模型回灌）。 */
    public record ToolRun(AgentDtos.ToolCallStep step, ToolOutcome outcome) {
    }

    public ToolRun execute(AuthUser user, Long storeId, String toolName, Map<String, Object> rawArgs, int round) {
        Map<String, Object> args = rawArgs == null ? Map.of() : new LinkedHashMap<>(rawArgs);
        AgentTool tool = registry.find(toolName)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "工具不存在：" + toolName + "（可用工具见 /api/agent/tools）"));

        guard.checkRateLimit(user);
        guard.checkPermission(user, tool);

        Optional<String> missing = registry.validateRequiredArgs(tool, args);
        if (missing.isPresent()) {
            // 不替模型瞎猜参数：缺参数就明确拒绝，让它（或用户）补齐
            ToolOutcome outcome = ToolOutcome.fail(missing.get());
            return new ToolRun(step(round, tool, args, outcome, 0), outcome);
        }

        long start = System.currentTimeMillis();
        ToolOutcome outcome;
        try {
            outcome = tool.execute(storeId, args);
        } catch (BizException e) {
            outcome = ToolOutcome.fail(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("工具执行异常 tool={} args={}", toolName, args, e);
            outcome = ToolOutcome.fail("工具执行失败：" + e.getClass().getSimpleName());
        }
        long elapsed = System.currentTimeMillis() - start;
        guard.audit(user, tool, args, outcome, elapsed);
        return new ToolRun(step(round, tool, args, outcome, elapsed), outcome);
    }

    private AgentDtos.ToolCallStep step(int round, AgentTool tool, Map<String, Object> args,
                                        ToolOutcome outcome, long elapsed) {
        String observation = outcome == null ? "" : outcome.forModel(OBSERVATION_MAX_CHARS);
        return new AgentDtos.ToolCallStep(round, tool.name(), args,
                outcome != null && outcome.success(), observation, elapsed, tool.requiresConfirmation());
    }
}
