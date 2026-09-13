package com.retailsuite.agent.tool;

import java.util.Map;

/**
 * 管家 Agent 的业务工具契约（声明式）。
 *
 * 企业级的三条硬约束都写在接口上，而不是散在实现里：
 * 1) {@link #permission()} —— 工具级权限码，复用 RBAC 体系。模型即使"想调"，没权限也调不动；
 * 2) {@link #readOnly()} —— 只读工具才允许自动执行；写操作（如生成采购单）只能产出**草稿**，
 *    必须人工在界面上确认，Agent 永远不直接改账；
 * 3) {@link #parameters()} —— 参数说明既喂给模型（让它知道传什么），也用于服务端必填校验
 *    （不信任模型给的参数，缺参数直接拒绝而不是瞎猜）。
 *
 * 实现类交给 Spring 管理（@Component），由 {@link AgentToolRegistry} 自动收集。
 */
public interface AgentTool {

    /** 工具名（模型用它点名调用），全小写下划线，例如 sales_ranking */
    String name();

    /** 给模型看的一句话说明：说清"什么时候用它" */
    String description();

    /** 参数说明：参数名 → 用途（必填参数请在用途里写明"必填"） */
    Map<String, String> parameters();

    /** 需要的权限码，如 report:read；空串表示只要求登录 */
    String permission();

    /** 是否只读。false 表示写操作，运行时只会让它产出草稿 */
    boolean readOnly();

    /**
     * 执行工具。
     *
     * @param storeId 当前门店（由登录态注入，**不接受模型传参**，避免越权查别家数据）
     * @param args    模型给的参数（已做必填校验前的原始值，实现里仍要自行兜底）
     */
    ToolOutcome execute(Long storeId, Map<String, Object> args);

    /** 默认实现里只读工具的安全网：写工具必须显式覆写为 false。 */
    default boolean requiresConfirmation() {
        return !readOnly();
    }
}
