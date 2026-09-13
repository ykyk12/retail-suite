package com.retailsuite.agent.controller;

import com.retailsuite.agent.dto.AgentDtos;
import com.retailsuite.agent.runtime.AgentRuntime;
import com.retailsuite.agent.tool.AgentToolRegistry;
import com.retailsuite.common.ApiResponse;
import com.retailsuite.security.RequiresPermission;
import com.retailsuite.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管家 Agent 接口。
 *
 * - {@code POST /api/agent/chat}：多轮对话（工具调用轨迹一并返回，前端可展示"它查了什么"）
 * - {@code GET /api/agent/tools}：当前账号可用的工具清单（能力透明，便于运维审计）
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Validated
public class AgentController {

    private final AgentRuntime agentRuntime;
    private final AgentToolRegistry toolRegistry;

    @PostMapping("/chat")
    @RequiresPermission("ai:use")
    @Operation(summary = "管家对话（多轮；写操作只产出草稿需人工确认）",
            description = "例：哪个商品最好卖 / 农夫山泉还有多少库存、多久过期 / 哪些临期要处理 / 今天卖了多少 / 帮我生成补货采购单草稿")
    public ApiResponse<AgentDtos.ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        var user = UserContext.require();
        return ApiResponse.ok(agentRuntime.chat(user, user.storeId(),
                new AgentDtos.ChatRequest(request.sessionId(), request.question())));
    }

    @GetMapping("/tools")
    @RequiresPermission("ai:use")
    @Operation(summary = "当前账号可用的管家工具清单（含权限码、是否只读）")
    public ApiResponse<List<Map<String, Object>>> tools() {
        return ApiResponse.ok(toolRegistry.describe(UserContext.require()));
    }

    @PostMapping("/session/reset")
    @RequiresPermission("ai:use")
    @Operation(summary = "开一个新会话（清掉当前上下文）",
            description = "不带 sessionId 的请求是「接着上次聊」，所以前端光丢掉本地 sessionId 清不掉上下文，必须调这个接口")
    public ApiResponse<Map<String, Object>> resetSession() {
        var user = UserContext.require();
        return ApiResponse.ok(Map.of("sessionId", agentRuntime.resetSession(user.userId())));
    }

    public record ChatRequest(
            @Size(max = 64, message = "sessionId 过长")
            String sessionId,
            @NotBlank(message = "请描述你想了解或处理的经营问题")
            @Size(max = 500, message = "问题过长")
            String question) {
    }
}
