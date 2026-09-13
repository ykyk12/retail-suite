package com.retailsuite.agent.tools;

import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 对账：销售数量与库存出库数量是否一致（不一致说明有人绕过收银动了库存）。 */
@Component
@RequiredArgsConstructor
public class ReconcileTool implements AgentTool {

    private final ReportService reportService;

    @Override
    public String name() {
        return "reconcile";
    }

    @Override
    public String description() {
        return "对账：核对某天的销售数量与库存出库数量是否一致（不一致即有异常，需要人工查因）";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("date", "对账日期 yyyy-MM-dd，默认今天");
        return params;
    }

    @Override
    public String permission() {
        return "report:read";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolOutcome execute(Long storeId, Map<String, Object> args) {
        LocalDate date = parseDate(args.get("date"));
        ReportDtos.Reconcile reconcile = reportService.reconcile(storeId, date);

        StringBuilder sb = new StringBuilder();
        sb.append(date).append(" 对账");
        if (reconcile.consistent()) {
            sb.append("一致：核对 ").append(reconcile.checkedProducts()).append(" 个商品，销售数量与库存出库完全对得上。");
        } else {
            sb.append("发现 ").append(reconcile.diffs().size()).append(" 个商品存在差异：");
            for (ReportDtos.ReconcileRow row : reconcile.diffs()) {
                sb.append("\n· ").append(row.productName())
                        .append(" 销售 ").append(row.soldQuantity())
                        .append(" 件，库存出库 ").append(row.flowQuantity())
                        .append(" 件，差 ").append(row.diff()).append(" 件");
            }
            sb.append("\n建议：核对是否存在绕过收银台的库存调整，或批次/流水写入异常。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", date.toString());
        data.put("consistent", reconcile.consistent());
        data.put("checkedProducts", reconcile.checkedProducts());
        data.put("diffs", reconcile.diffs().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", row.productId());
            item.put("productName", row.productName());
            item.put("soldQuantity", row.soldQuantity());
            item.put("flowQuantity", row.flowQuantity());
            item.put("diff", row.diff());
            return item;
        }).toList());
        return ToolOutcome.ok(sb.toString(), data);
    }

    private LocalDate parseDate(Object raw) {
        if (raw == null) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(String.valueOf(raw).trim());
        } catch (RuntimeException e) {
            return LocalDate.now();
        }
    }
}
