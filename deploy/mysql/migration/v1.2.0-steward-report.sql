-- ============================================================================
-- 升级脚本 v1.1.0 → v1.2.0：管家巡检日报（M3）
--
-- 适用：已经跑过 v1.1.0、库里有数据的 MySQL 实例（全新部署直接用 deploy/mysql/init/ 即可）
-- 执行：mysql -uroot -p retail_suite < deploy/mysql/migration/v1.2.0-steward-report.sql
--
-- 说明：
-- - 只新增一张表与一个唯一索引，不改动任何既有业务表，属于**可回滚的纯增量升级**
--   （回滚：DROP TABLE steward_report; 应用侧同时退回 v1.1.0 即可）。
-- - steward_report.findings 存 JSON：用 MEDIUMTEXT（utf8mb4 下中文 1 字符 3 字节，
--   TEXT 的 64KB 上限对一个几百商品的门店不够用——H2/VARCHAR(8000) 就是这么爆掉的）。
-- - 升级后无需人工补数据：报告由每天 07:30 的定时任务自动生成，
--   也可以立刻调用 POST /api/steward/inspect 手动生成一份验证。
-- ============================================================================

CREATE TABLE IF NOT EXISTS steward_report (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id      BIGINT        NOT NULL,
    report_date   DATE          NOT NULL COMMENT '报告日期（同门店同一天唯一，重复巡检覆盖）',
    source        VARCHAR(16)   NOT NULL COMMENT 'SCHEDULED 定时 / MANUAL 手动触发',
    headline      VARCHAR(500)  NOT NULL COMMENT '一句话总结（列表页与提醒用）',
    finding_count INT           NOT NULL DEFAULT 0,
    high_count    INT           NOT NULL DEFAULT 0 COMMENT '需立即处理的发现条数',
    findings      MEDIUMTEXT    NOT NULL COMMENT '结构化发现（JSON 数组，含指标与可执行动作）',
    generated_at  DATETIME      NOT NULL,
    updated_at    DATETIME      NOT NULL,
    deleted       TINYINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_steward_store_date (store_id, report_date),
    KEY idx_steward_store_date (store_id, report_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '管家巡检日报';
