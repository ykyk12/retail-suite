package com.retailsuite.report.controller;

import com.retailsuite.common.ApiResponse;
import com.retailsuite.report.dto.ReportDtos;
import com.retailsuite.report.excel.ExcelExporter;
import com.retailsuite.report.service.ReportService;
import com.retailsuite.security.RequiresPermission;
import com.retailsuite.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** 报表接口：概览、日报、TOP 商品、对账、Excel 导出。 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final ExcelExporter excelExporter;

    @GetMapping("/overview")
    @RequiresPermission("report:read")
    @Operation(summary = "经营概览（实时聚合，默认今天）")
    public ApiResponse<ReportDtos.Overview> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(reportService.overview(UserContext.requireStoreId(), date));
    }

    @GetMapping("/daily")
    @RequiresPermission("report:read")
    @Operation(summary = "日报（读汇总表；如需最新数值可先调 rebuild）")
    public ApiResponse<List<ReportDtos.DailyRow>> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reportService.dailySummaries(UserContext.requireStoreId(), from, to));
    }

    @GetMapping("/top-products")
    @RequiresPermission("report:read")
    @Operation(summary = "TOP 商品（按销售额倒序，含毛利）")
    public ApiResponse<List<ReportDtos.TopProduct>> topProducts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(reportService.topProducts(UserContext.requireStoreId(), from, to, limit));
    }

    @GetMapping("/reconcile")
    @RequiresPermission("report:read")
    @Operation(summary = "对账：销售数量 vs 库存出库数量（不一致即有异常）")
    public ApiResponse<ReportDtos.Reconcile> reconcile(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(reportService.reconcile(UserContext.requireStoreId(), date));
    }

    @PostMapping("/daily/{date}/rebuild")
    @RequiresPermission("report:read")
    @Operation(summary = "重算某天汇总（幂等）")
    public ApiResponse<ReportDtos.DailyRow> rebuild(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(reportService.rebuildDailySummary(UserContext.requireStoreId(), date));
    }

    @GetMapping("/export/daily")
    @RequiresPermission("report:read")
    @Operation(summary = "导出日报 Excel（流式写入，支持大数据量）")
    public void exportDaily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletResponse response) throws IOException {
        Long storeId = UserContext.requireStoreId();
        List<List<Object>> rows = reportService.exportDailyRows(storeId, from, to);
        String fileName = "日报_" + from + "_" + to + ".xlsx";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 文件名含中文：用 RFC 5987 的 filename* 形式，避免浏览器乱码
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        excelExporter.write(response.getOutputStream(), "日报",
                List.of("日期", "订单数", "商品件数", "销售额", "退款额", "净销售额", "毛利"), rows);
    }
}
