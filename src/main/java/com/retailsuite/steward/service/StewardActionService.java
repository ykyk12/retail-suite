package com.retailsuite.steward.service;

import com.retailsuite.agent.runtime.AgentToolExecutor;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.security.AuthUser;
import com.retailsuite.steward.dto.StewardDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把巡检日报里的建议变成动作。
 *
 * 设计要点：**动作走 Agent 工具执行器，而不是直接调采购服务**。
 * 因为"一键生成采购草稿"同样是 Agent 的一次写动作，必须与大模型路径享受完全相同的待遇：
 * 权限码校验（purchase:write）、每用户限流、必填参数校验、写入 AGENT_TOOL_CALL 审计。
 * 若这里图省事直接 new 一个采购单，就会出现"界面上点一下不用权限、Agent 调却要权限"的双标。
 *
 * 边界依旧：只生成 DRAFT 采购单，库存不变；确认入库仍需人工在采购页操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StewardActionService {

    public static final String ACTION_PURCHASE_DRAFT = "CREATE_PURCHASE_DRAFT";

    private final StewardInspectionService inspectionService;
    private final AgentToolExecutor toolExecutor;

    /**
     * 执行某条发现上的动作。
     *
     * @param findingCode 发现编码（如 REORDER）——同一份报告里可能有多个发现，按编码定位
     */
    public StewardDtos.ActionResult execute(AuthUser user, Long storeId, Long reportId, String findingCode,
                                           String actionType) {
        StewardDtos.ReportView report = inspectionService.find(storeId, reportId);
        StewardDtos.Finding finding = report.findings().stream()
                .filter(f -> f.code().equalsIgnoreCase(findingCode))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "这份报告里没有「" + findingCode + "」这条发现"));
        String type = actionType == null || actionType.isBlank() ? ACTION_PURCHASE_DRAFT : actionType;
        StewardDtos.Action action = finding.actions().stream()
                .filter(a -> a.type().equalsIgnoreCase(type))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "发现「" + finding.title() + "」不支持动作 " + type));
        if (!action.executable()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该动作需要人工处理：" + action.hint());
        }
        if (!ACTION_PURCHASE_DRAFT.equalsIgnoreCase(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知动作类型：" + type);
        }

        Map<String, Object> args = purchaseArgs(finding, action);
        AgentToolExecutor.ToolRun run = toolExecutor.execute(user, storeId, "draft_purchase_order", args, 0);
        if (!run.outcome().success()) {
            // 工具失败（多半是权限不足）：如实抛出，不假装成功
            throw new BizException(ErrorCode.FORBIDDEN, run.outcome().summary());
        }
        return new StewardDtos.ActionResult(type, run.outcome().summary(), run.outcome().data());
    }

    /** 由发现里的建议明细拼出工具参数：用当时报告里的数量与进价，不重新计算。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> purchaseArgs(StewardDtos.Finding finding, StewardDtos.Action action) {
        Object rawItems = action.payload() == null ? null : action.payload().get("items");
        if (!(rawItems instanceof List<?> list) || list.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这条建议里没有可下单的明细");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> row)) {
                continue;
            }
            Object productId = row.get("productId");
            Object quantity = row.get("suggestQuantity");
            if (productId == null || quantity == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", productId);
            item.put("quantity", quantity);
            item.put("unitCost", row.get("unitCost"));
            items.add(item);
        }
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这条建议里没有可下单的明细");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("items", items);
        args.put("remark", "由管家巡检日报（发现：" + finding.code() + "）生成草稿");
        return args;
    }
}
