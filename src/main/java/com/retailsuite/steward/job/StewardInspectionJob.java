package com.retailsuite.steward.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.steward.service.StewardInspectionService;
import com.retailsuite.store.entity.Store;
import com.retailsuite.store.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 管家主动巡检定时任务：每天清晨把每个门店的经营情况过一遍，生成当天的巡检日报。
 *
 * 为什么放在"开门前"：店长早上到店第一件事就能看到"哪个批次今天过期、哪个商品要断货"，
 * 而不是等到出了问题再回头查数据。默认 07:30（配置项 {@code app.steward.inspect-cron}）。
 *
 * 与日汇总任务同样的纪律：**一个门店失败不影响其它门店**，异常只记日志等下一个周期重试；
 * 巡检本身幂等（同一天覆盖同一行），重复执行不会产生多份报告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StewardInspectionJob {

    private final StewardInspectionService inspectionService;
    private final StoreMapper storeMapper;

    @Scheduled(cron = "${app.steward.inspect-cron:0 30 7 * * ?}")
    public void inspectAllStores() {
        int ok = 0;
        int failed = 0;
        for (Store store : storeMapper.selectList(new LambdaQueryWrapper<Store>().eq(Store::getStatus, 1))) {
            try {
                inspectionService.inspect(store.getId(), StewardInspectionService.SOURCE_SCHEDULED);
                ok++;
            } catch (RuntimeException e) {
                failed++;
                log.error("门店 {} 巡检失败（下一个周期会重试）：{}", store.getId(), e.toString());
            }
        }
        log.info("管家巡检任务完成：成功 {} 个门店，失败 {} 个", ok, failed);
    }
}
