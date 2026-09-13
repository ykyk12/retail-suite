package com.retailsuite.steward.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管家巡检日报的 DTO。
 *
 * 关键设计：**发现（Finding）自带动作（Action）**。
 * 日报不是"一堆字"，而是一串结构化条目——前端能渲染成卡片、能按严重度排序、
 * 而"补货"这类动作可以直接一键转成采购单草稿（payload 就是当时的建议明细，
 * 不重新计算，保证"你看到的数字"和"生成单据用的数字"完全一致）。
 */
public final class StewardDtos {

    private StewardDtos() {
    }

    /**
     * 一条巡检发现。
     *
     * @param code     机器可读的编码（EXPIRED_BATCH / STOCKOUT_RISK …），前端据此决定图标与跳转
     * @param severity HIGH 需立即处理 / WARN 需要关注 / INFO 只是提示
     * @param title    一句话结论
     * @param detail   依据（含具体数字，可读文本）
     * @param metrics  结构化指标（金额、数量、明细列表），供前端渲染与动作复用
     * @param actions  可执行动作（只读建议型动作为空）
     */
    public record Finding(String code,
                          String severity,
                          String title,
                          String detail,
                          Map<String, Object> metrics,
                          List<Action> actions) {
    }

    /**
     * 可执行动作。
     *
     * @param type       动作类型，目前只有 CREATE_PURCHASE_DRAFT
     * @param label      按钮文案
     * @param executable 是否可由系统直接执行（写操作一律仍是"草稿"，人工确认后才落账）
     * @param hint       给使用者的说明（写清"只是草稿、不会改库存"）
     * @param payload    执行所需参数（如采购明细），与 metrics 同源
     */
    public record Action(String type, String label, boolean executable, String hint, Map<String, Object> payload) {
    }

    /** 一次巡检的完整结果。 */
    public record ReportView(Long id,
                             LocalDate reportDate,
                             String source,
                             LocalDateTime generatedAt,
                             String headline,
                             int findingCount,
                             int highCount,
                             List<Finding> findings) {
    }

    /** 执行动作的返回。 */
    public record ActionResult(String actionType, String message, Map<String, Object> data) {
    }

    /** 执行动作的入参（不传则用该发现上的默认动作类型）。 */
    public record ActionRequest(String actionType) {
    }
}
