package com.retailsuite.agent.tool;

import com.retailsuite.security.AuthUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：Spring 启动时自动收集所有 {@link AgentTool} 实现。
 *
 * 两个企业级细节：
 * 1) **按当前用户权限过滤**：没权限的工具既不出现在给模型的目录里，也不允许被调用（双保险）。
 *    只靠"提示词里不写"是不安全的——模型完全可能凭常识猜出一个工具名；
 * 2) **目录即契约**：目录文本同时用于系统提示词和 /api/agent/tools（前端/运维可查看），
 *    避免"能力清单"在文档和代码里各写一份、慢慢漂移。
 */
@Slf4j
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public AgentToolRegistry(List<AgentTool> discovered) {
        discovered.stream()
                .sorted(Comparator.comparing(AgentTool::name))
                .forEach(tool -> {
                    AgentTool previous = tools.put(tool.name(), tool);
                    if (previous != null) {
                        log.warn("工具名重复，后者覆盖前者：{}（{} → {}）", tool.name(),
                                previous.getClass().getSimpleName(), tool.getClass().getSimpleName());
                    }
                });
        log.info("管家 Agent 工具注册完成：{} 个（只读 {} 个，需确认 {} 个）",
                tools.size(),
                tools.values().stream().filter(AgentTool::readOnly).count(),
                tools.values().stream().filter(AgentTool::requiresConfirmation).count());
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<AgentTool> all() {
        return List.copyOf(tools.values());
    }

    /** 当前用户可见（有权限）的工具。 */
    public List<AgentTool> visibleTo(AuthUser user) {
        return tools.values().stream()
                .filter(tool -> permitted(tool, user))
                .toList();
    }

    public boolean permitted(AgentTool tool, AuthUser user) {
        String permission = tool.permission();
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return user != null && user.hasPermission(permission);
    }

    /** 工具目录文本（喂给模型）：只包含当前用户有权限的工具。 */
    public String catalogFor(AuthUser user) {
        List<AgentTool> visible = visibleTo(user);
        if (visible.isEmpty()) {
            return "(当前账号没有可用工具)";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentTool tool : visible) {
            sb.append("- ").append(tool.name())
                    .append(tool.readOnly() ? "（只读）" : "（写操作：只生成草稿，需人工确认）")
                    .append("：").append(tool.description())
                    .append(" 参数=").append(tool.parameters())
                    .append('\n');
        }
        return sb.toString();
    }

    /** 供 /api/agent/tools 展示的能力清单。 */
    public List<Map<String, Object>> describe(AuthUser user) {
        return visibleTo(user).stream().map(tool -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", tool.name());
            row.put("description", tool.description());
            row.put("parameters", tool.parameters());
            row.put("permission", tool.permission());
            row.put("readOnly", tool.readOnly());
            row.put("requiresConfirmation", tool.requiresConfirmation());
            return row;
        }).toList();
    }

    /** 校验模型给的参数是否满足必填约束（必填 = 参数说明里含"必填"）。 */
    public Optional<String> validateRequiredArgs(AgentTool tool, Map<String, Object> args) {
        for (Map.Entry<String, String> entry : tool.parameters().entrySet()) {
            if (!entry.getValue().contains("必填")) {
                continue;
            }
            Object value = args.get(entry.getKey());
            if (value == null || String.valueOf(value).isBlank()) {
                return Optional.of("缺少必填参数 " + entry.getKey() + "（" + entry.getValue() + "）");
            }
        }
        return Optional.empty();
    }
}
