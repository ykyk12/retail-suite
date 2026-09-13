package com.retailsuite.report.job;

import com.retailsuite.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 日汇总定时任务。
 *
 * 默认每天 00:10 执行（配置项 {@code app.report.daily-summary-cron}）：
 * - 重算**昨天**（完整一天，用于趋势与导出）；
 * - 顺带重算**今天**（当天已过部分，方便白天打开看板就能看到数据）。
 * 任务幂等：同一天重复执行只是覆盖同一行，不会产生重复数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySummaryJob {

    private final ReportService reportService;

    @Scheduled(cron = "${app.report.daily-summary-cron:0 10 0 * * ?}")
    public void summarize() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try {
            int stores = reportService.rebuildAllStores(yesterday);
            log.info("日汇总任务完成：昨天({}) 覆盖 {} 个门店", yesterday, stores);
        } catch (RuntimeException e) {
            log.error("日汇总任务失败（下一个周期会重试）：{}", e.toString());
        }
    }
}
