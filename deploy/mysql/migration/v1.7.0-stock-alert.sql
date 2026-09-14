-- ============================================================================
-- 升级脚本：低库存预警工单（闭环）
-- 适用：已经跑过 v1.2.0、库里有数据的 MySQL 实例（全新部署直接用 deploy/mysql/init/ 即可）
-- 执行：mysql -uroot -p retail_suite < deploy/mysql/migration/v1.7.0-stock-alert.sql
--
-- 说明：
-- - 只新增一张表，不改任何既有业务表，属于可回滚的纯增量升级；
--   （回滚：DROP TABLE stock_alert; 应用同步退回旧版即可）
-- - 出库把库存砸到阈值以下时，应用在同一事务内自动开一条 OPEN 工单；
--   同一商品已有 OPEN 工单不重复开；店长补货/盘点后调 POST /api/inventory/alerts/{id}/resolve 闭环。
-- - 无需人工补数据：老数据不会自动补单，下一次自然出库触发即可。
-- ============================================================================

CREATE TABLE IF NOT EXISTS stock_alert (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id        BIGINT       NOT NULL COMMENT '所属门店（隔离）',
    product_id      BIGINT       NOT NULL COMMENT '预警商品',
    product_name    VARCHAR(128) COMMENT '冗余商品名，列表展示免 join',
    stock_at_alert  INT          NOT NULL DEFAULT 0 COMMENT '触发预警时的库存快照',
    threshold       INT          NOT NULL DEFAULT 0 COMMENT '触发时的阈值',
    status          VARCHAR(16)  NOT NULL COMMENT 'OPEN 待处理 / RESOLVED 已闭环',
    resolve_remark  VARCHAR(200) COMMENT '闭环备注（补货数量/到货日期等）',
    resolved_by     BIGINT COMMENT '闭环操作人',
    resolved_at     DATETIME COMMENT '闭环时间',
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    KEY idx_stock_alert_store_status (store_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '低库存预警工单（闭环）';
