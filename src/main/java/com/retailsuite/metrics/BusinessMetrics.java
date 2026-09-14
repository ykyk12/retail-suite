package com.retailsuite.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 业务可观测指标：把"登录 / 收银 / 出库 / 退货"等关键业务事件接到 Micrometer，
 * 自动出现在 /actuator/metrics 下。
 *
 * 对标思路（mall / mall-swarm 这类成熟商城把"监控中心"作为标配）：
 * 先把核心业务事件量化成指标，再谈看板与告警——没有指标就谈不上"线上到底跑得对不对"。
 *
 * 为什么薄封装一层，而不是到处注入 MeterRegistry：
 *  1) 指标名、tag 集中在一处，改名/对齐 Prometheus 命名只动这里；
 *  2) 采集失败绝不允许影响主业务（和审计服务同一个原则）：这里每一处都兜底，
 *     监控是"锦上添花"，不能因为埋点把收银搞挂。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessMetrics {

    private final MeterRegistry registry;

    /** 登录成功（按门店打 tag，便于多门店拆分看板）。 */
    public void loginSuccess(Long storeId) {
        incr("app.login.success", storeId, null);
    }

    /** 登录失败（用户名/密码错、账号停用等）。 */
    public void loginFailure(Long storeId) {
        incr("app.login.failure", storeId, null);
    }

    /** 收银成功：按支付方式打 tag，看板可按 CASH/WECHAT/ALIPAY/CARD 拆分。 */
    public void checkoutSuccess(Long storeId, String payMethod) {
        counter("app.sale.checkout", storeId, "payMethod", payMethod == null ? "UNKNOWN" : payMethod).increment();
    }

    /** 出库时库存不足被拦截（防超卖兜底触发次数，是"是否真的卖得动货"的反向信号）。 */
    public void outOfStock(Long storeId) {
        incr("app.sale.out_of_stock", storeId, null);
    }

    /** 退货成功。 */
    public void refundSuccess(Long storeId) {
        incr("app.sale.refund", storeId, null);
    }

    private void incr(String name, Long storeId, String ignored) {
        counter(name, storeId, null, null).increment();
    }

    private Counter counter(String name, Long storeId, String tagKey, String tagValue) {
        try {
            Counter.Builder builder = Counter.builder(name).tags("storeId", String.valueOf(storeId));
            if (tagKey != null) {
                builder.tags(tagKey, tagValue);
            }
            return builder.register(registry);
        } catch (RuntimeException e) {
            // 指标注册失败不能影响主业务：返回一个不做事的 Noop 计数，调用方照常 ++
            log.warn("业务指标注册失败（已忽略，不影响主流程）name={} err={}", name, e.toString());
            return Counter.builder(name).register(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }
    }
}
