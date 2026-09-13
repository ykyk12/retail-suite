package com.retailsuite.agent.tool;

/**
 * 工具执行结果。
 *
 * summary 是回灌给模型的自然语言观察（要简短、结论明确）；
 * 结构化数据放在 data 里，便于接口层直接返回给前端做卡片展示——两者分开，避免"给模型看"和"给人看"互相将就。
 */
public record ToolOutcome(boolean success, String summary, java.util.Map<String, Object> data) {

    public static ToolOutcome ok(String summary) {
        return new ToolOutcome(true, summary, java.util.Map.of());
    }

    public static ToolOutcome ok(String summary, java.util.Map<String, Object> data) {
        return new ToolOutcome(true, summary, data);
    }

    public static ToolOutcome fail(String summary) {
        return new ToolOutcome(false, summary, java.util.Map.of());
    }

    /** 回灌给模型前做长度保护：工具输出可能很长，不能把上下文挤爆。 */
    public String forModel(int maxChars) {
        String prefix = success ? "OK: " : "FAILED: ";
        String body = summary == null ? "" : summary;
        if (body.length() > maxChars) {
            body = body.substring(0, maxChars) + "...(已截断)";
        }
        return prefix + body;
    }
}
