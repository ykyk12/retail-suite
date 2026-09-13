package com.retailsuite.steward.controller;

import com.retailsuite.common.ApiResponse;
import com.retailsuite.security.RequiresPermission;
import com.retailsuite.security.UserContext;
import com.retailsuite.steward.dto.StewardDtos;
import com.retailsuite.steward.service.StewardActionService;
import com.retailsuite.steward.service.StewardInspectionService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管家巡检接口。
 *
 * 权限设计：
 * - 看报告 / 手动触发巡检：只需 {@code report:read}——巡检是只读分析，"生成报告"不改任何账实数据；
 * - 执行动作（一键转采购草稿）：入口要求 {@code ai:use}（这是管家能力），
 *   真正的写权限由 {@link com.retailsuite.agent.runtime.AgentGuard} 按工具声明校验 {@code purchase:write}，
 *   与对话路径完全同一条链路——不会出现"界面上点按钮不用权限"的双标。
 */
@RestController
@RequestMapping("/api/steward")
@RequiredArgsConstructor
public class StewardController {

    private final StewardInspectionService inspectionService;
    private final StewardActionService actionService;

    @PostMapping("/inspect")
    @RequiresPermission("report:read")
    @Operation(summary = "立即巡检一次并生成/更新今天的管家日报",
            description = "定时任务每天开门前自动跑；这个接口用于店长想随时看一份最新的")
    public ApiResponse<StewardDtos.ReportView> inspect() {
        return ApiResponse.ok(inspectionService.inspect(UserContext.requireStoreId(),
                StewardInspectionService.SOURCE_MANUAL));
    }

    @GetMapping("/reports/latest")
    @RequiresPermission("report:read")
    @Operation(summary = "最近一份管家日报")
    public ApiResponse<StewardDtos.ReportView> latest() {
        return ApiResponse.ok(inspectionService.latest(UserContext.requireStoreId()));
    }

    @GetMapping("/reports")
    @RequiresPermission("report:read")
    @Operation(summary = "历史管家日报（按日期倒序）")
    public ApiResponse<List<StewardDtos.ReportView>> reports(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(inspectionService.recent(UserContext.requireStoreId(), limit));
    }

    @GetMapping("/reports/{id}")
    @RequiresPermission("report:read")
    @Operation(summary = "某份管家日报详情")
    public ApiResponse<StewardDtos.ReportView> detail(@PathVariable Long id) {
        return ApiResponse.ok(inspectionService.find(UserContext.requireStoreId(), id));
    }

    @PostMapping("/reports/{id}/findings/{code}/actions")
    @RequiresPermission("ai:use")
    @Operation(summary = "执行某条发现上的动作（目前只有：一键生成采购单草稿）",
            description = "动作走 Agent 工具执行器：权限、限流、审计与对话路径完全一致；" +
                    "只生成草稿，库存不变，确认入库仍需人工在采购页操作")
    public ApiResponse<StewardDtos.ActionResult> executeAction(@PathVariable Long id,
                                                               @PathVariable String code,
                                                               @RequestBody(required = false) StewardDtos.ActionRequest request) {
        var user = UserContext.require();
        String type = request == null ? null : request.actionType();
        return ApiResponse.ok(actionService.execute(user, user.storeId(), id, code, type));
    }
}
