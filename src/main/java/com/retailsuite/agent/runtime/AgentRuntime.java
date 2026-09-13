package com.retailsuite.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailsuite.agent.dto.AgentDtos;
import com.retailsuite.agent.tool.AgentToolRegistry;
import com.retailsuite.ai.llm.LlmClient;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.config.AppProperties;
import com.retailsuite.security.AuthUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 管家 Agent 运行时：把"用户的一句话"变成"查了哪些数、得出什么结论"。
 *
 * 执行模型（企业级要点）：
 * 1) **工具目录按权限过滤**后写进系统提示词——模型只看见当前账号能用的工具；
 * 2) 模型每轮只输出一个 JSON 动作：{@code {"action":"tool",...}} 或 {@code {"action":"answer",...}}；
 * 3) 每次工具调用都过 {@link AgentToolExecutor}（权限 + 限流 + 审计 + 必填校验）；
 * 4) **写操作只产出草稿**：工具自身实现为"生成草稿"，步骤里标记 requiresConfirmation，人工确认后才落单；
 * 5) 模型不可用 / 输出不是合法 JSON / 超过步数上限 → 回退 {@link RuleIntentRouter}（规则兜底），
 *    并把 source 标成 RULE，让使用者知道这次回答不是模型给的；
 * 6) 每轮结果写进 {@link AgentSessionStore}（多轮上下文），并按上限裁剪。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntime {

    private static final String SYSTEM_PROMPT = """
            你是「云小店」的零售管家 Agent，帮店长与店员做经营分析与日常事务。
            你可以调用工具查真实数据；只输出一个 JSON 对象，不要输出解释文字或 Markdown 代码块。
            调用工具：{"action":"tool","tool":"<工具名>","args":{...},"thought":"为什么查它"}
            给出结论：{"action":"answer","answer":"<中文结论，含具体数字，不要编造>","thought":"依据"}
            铁律：
            1) 任何数字都必须来自工具返回，禁止凭常识推测；工具失败就如实说明；
            2) 写操作（如生成采购单）只会产出草稿，你需要明确告诉用户"已生成草稿，请人工确认"；
            3) 涉及保质期/临期/过期时，必须给出到期日或剩余天数；
            4) 一轮对话最多调用工具 %d 次，数据够了就立刻给结论，不要反复查同一个工具。
            可用工具（已按当前账号权限过滤）：
            %s
            """;

    private final AgentToolRegistry registry;
    private final AgentToolExecutor executor;
    private final AgentSessionStore sessionStore;
    private final RuleIntentRouter ruleIntentRouter;
    private final LlmClient llmClient;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    /** 管家对话入口。 */
    public AgentDtos.ChatResponse chat(AuthUser user, Long storeId, AgentDtos.ChatRequest request) {
        String question = request == null || request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请描述你想了解或处理的经营问题");
        }
        AgentSessionStore.AgentSession session = sessionStore.getOrCreate(user.userId(),
                request == null ? null : request.sessionId());

        AgentDtos.ChatResponse response = null;
        if (llmClient.configured()) {
            response = chatWithLlm(user, storeId, session, question).orElse(null);
            if (response == null) {
                log.info("模型路径不可用，回退规则兜底 session={}", session.sessionId());
            }
        }
        if (response == null) {
            if (ruleIntentRouter.unknownIntent(question)) {
                response = ruleIntentRouter.capability(user, storeId, question);
            } else {
                response = ruleIntentRouter.answer(user, storeId, question);
            }
        }
        session.append(new AgentSessionStore.Turn(question, response.answer(), response.toolsUsed(),
                LocalDateTime.now()));
        session.trim(properties.getAi().getSessionMaxTurns());
        return new AgentDtos.ChatResponse(session.sessionId(), response.answer(), response.source(),
                response.toolsUsed(), response.steps(), response.cards());
    }

    /** 开一个新会话（清掉该用户当前上下文）。 */
    public String resetSession(Long userId) {
        return sessionStore.reset(userId);
    }

    /** 模型路径；任何一步不可用（未配置/网络失败/非法 JSON）都返回 empty 交给规则兜底。 */
    private Optional<AgentDtos.ChatResponse> chatWithLlm(AuthUser user, Long storeId,
                                                        AgentSessionStore.AgentSession session, String question) {
        int maxSteps = Math.max(1, properties.getAi().getAgentMaxSteps());
        String history = session.historyDigest(properties.getAi().getSessionMaxTurns());
        String system = SYSTEM_PROMPT.formatted(maxSteps, registry.catalogFor(user));

        List<AgentDtos.ToolCallStep> steps = new ArrayList<>();
        List<String> toolsUsed = new ArrayList<>();
        List<Map<String, Object>> cards = new ArrayList<>();
        String prompt = "（此前对话摘要）\n" + history + "\n\n（本轮问题）\n" + question;

        for (int round = 0; round < maxSteps; round++) {
            Optional<String> raw = llmClient.chatJson(system, prompt);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> action;
            try {
                action = objectMapper.readValue(extractJson(raw.get()), new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("模型输出不是合法 JSON，回退规则路径：{}", e.getMessage());
                return Optional.empty();
            }
            String kind = String.valueOf(action.getOrDefault("action", "answer"));
            if (!"tool".equals(kind)) {
                String answer = String.valueOf(action.getOrDefault("answer", "")).trim();
                if (answer.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new AgentDtos.ChatResponse(session.sessionId(), answer, "LLM",
                        toolsUsed, steps, cards));
            }
            String toolName = String.valueOf(action.getOrDefault("tool", ""));
            Map<String, Object> args = action.get("args") instanceof Map<?, ?> map
                    ? toStringMap(map) : Map.of();
            AgentToolExecutor.ToolRun run;
            try {
                run = executor.execute(user, storeId, toolName, args, round);
            } catch (BizException e) {
                // 权限不足/超限流：把原因回给模型，让它换工具或如实告知用户（不吞掉）
                steps.add(new AgentDtos.ToolCallStep(round, toolName, args, false,
                        "FAILED: " + e.getMessage(), 0, false));
                prompt = question + "\n\n（工具 " + toolName + " 被拒绝：" + e.getMessage() + "，请改用你有权限的工具或如实说明原因）";
                continue;
            }
            steps.add(run.step());
            toolsUsed.add(toolName);
            if (!run.outcome().data().isEmpty()) {
                cards.add(run.outcome().data());
            }
            prompt = question + "\n\n（第 " + (round + 1) + " 轮工具 " + toolName + " 的返回）\n"
                    + run.outcome().forModel(2000)
                    + "\n数据够了就直接给 answer，否则继续调下一个工具。";
        }
        log.info("模型路径达到最大步数 {} 仍未给结论，回退规则兜底", maxSteps);
        return Optional.empty();
    }

    private Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> map.put(String.valueOf(key), value));
        return map;
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }
}
