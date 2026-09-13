package com.retailsuite;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 云小店 —— 小微零售进销存与收银系统。
 *
 * 架构选择（面试常问）：单体分层而**不是**微服务。
 * 原因：库存扣减、订单落库、流水记账必须在同一个本地事务里完成；
 * 拆成微服务会把一个事务拆成分布式事务，引入对账与补偿复杂度，而本项目单店/小连锁的
 * 数据量与团队规模完全用不上这种复杂度。真到了多店高并发阶段，再按"订单/库存/报表"拆分也不迟。
 */
@SpringBootApplication
@MapperScan("com.retailsuite.**.mapper")
@EnableScheduling
public class RetailSuiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetailSuiteApplication.class, args);
    }
}
