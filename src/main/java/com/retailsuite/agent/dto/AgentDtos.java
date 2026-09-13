package com.retailsuite.agent.dto;

import java.util.List;
import java.util.Map;

/** 管家 Agent 的接口 DTO。 */
public final class AgentDtos {

    private AgentDtos() {
    }

    public record ChatRequest(String sessionId, String question) {
    }

    /** 一次工具调用的执行轨迹（前端可折叠展示，运维可据此排查"它为什么这么答"）。 */
    public record ToolCallStep(int round,
                               String tool,
                               Map<String, Object> args,
                               boolean success,
                               String observation,
                               long elapsedMillis,
                               boolean requiresConfirmation) {
    }

    /** 管家的回答。source 标明是模型作答还是规则兜底，让使用者知道可信度来源。 */
    public record ChatResponse(String sessionId,
                               String answer,
                               String source,
                               List<String> toolsUsed,
                               List<ToolCallStep> steps,
                               List<Map<String, Object>> cards) {
    }
}
