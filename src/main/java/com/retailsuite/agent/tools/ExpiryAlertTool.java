package com.retailsuite.agent.tools;

import com.retailsuite.agent.tool.AgentTool;
import com.retailsuite.agent.tool.ToolOutcome;
import com.retailsuite.inventory.dto.InventoryDtos;
import com.retailsuite.inventory.service.ExpiryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 保质期管家：临期批次（多久到期、压了多少钱）与已过期批次（必须下架报损）。 */
@Component
@RequiredArgsConstructor
public class ExpiryAlertTool implements AgentTool {

    private final ExpiryService expiryService;

    @Override
    public String name() {
        return "expiry_alert";
    }

    @Override
    public String description() {
        return "查保质期情况：哪些批次临期（含剩余天数与压货金额）、哪些已经过期需要下架报损";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("days", "临期判定天数，默认取门店配置（30 天）");
        params.put("includeExpired", "是否包含已过期批次，默认 true");
        return params;
    }

    @Override
    public String permission() {
        return "inventory:read";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolOutcome execute(Long storeId, Map<String, Object> args) {
        Integer days = intArg(args.get("days"));
        boolean includeExpired = !"false".equalsIgnoreCase(String.valueOf(args.getOrDefault("includeExpired", "true")));

        List<InventoryDtos.BatchView> expiring = expiryService.expiring(storeId, days);
        List<InventoryDtos.BatchView> expired = includeExpired ? expiryService.expired(storeId) : List.of();
        InventoryDtos.ExpirySummary summary = expiryService.summary(storeId, days);

        StringBuilder sb = new StringBuilder();
        sb.append("临期（").append(summary.alertDays()).append(" 天内到期）：")
                .append(summary.expiringBatchCount()).append(" 个批次，共 ")
                .append(summary.expiringQuantity()).append(" 件，占用成本 ")
                .append(summary.expiringAmount()).append(" 元");
        if (expiring.isEmpty()) {
            sb.append("（没有临期批次）");
        } else {
            for (InventoryDtos.BatchView batch : expiring) {
                sb.append("\n· ").append(batch.productName())
                        .append(" 批次 ").append(batch.batchNo())
                        .append("：").append(batch.quantity()).append(" 件，")
                        .append("到期 ").append(batch.expiryDate())
                        .append("（剩 ").append(batch.daysToExpiry()).append(" 天）")
                        .append("，成本 ").append(batch.costPrice()).append(" 元/件");
            }
            sb.append("\n建议：对临期批次做促销或设置近效期专属价，优先把它卖出去。");
        }
        if (includeExpired) {
            sb.append("\n已过期：").append(summary.expiredBatchCount()).append(" 个批次，共 ")
                    .append(summary.expiredQuantity()).append(" 件，占用成本 ")
                    .append(summary.expiredAmount()).append(" 元");
            if (expired.isEmpty()) {
                sb.append("（没有过期批次）");
            } else {
                for (InventoryDtos.BatchView batch : expired) {
                    sb.append("\n· ").append(batch.productName())
                            .append(" 批次 ").append(batch.batchNo())
                            .append("：").append(batch.quantity()).append(" 件，已于 ")
                            .append(batch.expiryDate()).append(" 过期");
                }
                sb.append("\n提醒：过期商品必须立即下架并按报损处理（走库存报损，不能继续销售）。");
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alertDays", summary.alertDays());
        data.put("expiringCount", summary.expiringBatchCount());
        data.put("expiringQuantity", summary.expiringQuantity());
        data.put("expiringAmount", summary.expiringAmount());
        data.put("expiredCount", summary.expiredBatchCount());
        data.put("expiredQuantity", summary.expiredQuantity());
        data.put("expiredAmount", summary.expiredAmount());
        data.put("expiringBatches", expiring.stream().map(this::toRow).toList());
        data.put("expiredBatches", expired.stream().map(this::toRow).toList());
        return ToolOutcome.ok(sb.toString(), data);
    }

    private Map<String, Object> toRow(InventoryDtos.BatchView batch) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productId", batch.productId());
        row.put("productName", batch.productName());
        row.put("batchNo", batch.batchNo());
        row.put("quantity", batch.quantity());
        row.put("expiryDate", batch.expiryDate() == null ? null : batch.expiryDate().toString());
        row.put("daysToExpiry", batch.daysToExpiry());
        row.put("expired", batch.expired());
        return row;
    }

    private Integer intArg(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? null : Integer.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
