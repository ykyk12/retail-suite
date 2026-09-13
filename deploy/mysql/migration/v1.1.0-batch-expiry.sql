-- ============================================================================
-- 升级脚本 v1.0.0 → v1.1.0：批次与保质期（M1）
--
-- 适用：已经跑过旧版本、库里有数据的 MySQL 实例（全新部署直接用 deploy/mysql/init/ 即可）
-- 执行：mysql -uroot -p retail_suite < deploy/mysql/migration/v1.1.0-batch-expiry.sql
--
-- 说明：
-- - MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，所以这里是普通 ALTER；
--   重复执行会报 "Duplicate column name"，属预期（说明已升级过），可忽略或先检查 information_schema。
-- - 已有库存不会自动补批次（旧数据没有生产日期，无法推算到期日）；
--   上线后请走一次"盘点调整"或直接确认一张采购单，让库存重新落到批次上，
--   然后用 GET /api/inventory/batch-mismatch 核对：不一致的条目按同样方式补批次，直到返回空数组。
-- ============================================================================

ALTER TABLE product
    ADD COLUMN shelf_life_days INT NULL COMMENT '保质期天数；NULL 表示不追踪保质期（日用品）';

ALTER TABLE inventory_flow
    ADD COLUMN batch_id BIGINT NULL COMMENT '关联批次';

ALTER TABLE purchase_order_item
    ADD COLUMN production_date DATE NULL COMMENT '生产日期（到期日 = 生产日期 + 保质期）',
    ADD COLUMN shelf_life_days INT NULL COMMENT '保质期天数，留空用商品档案';

CREATE TABLE IF NOT EXISTS product_batch (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id          BIGINT        NOT NULL,
    product_id        BIGINT        NOT NULL,
    batch_no          VARCHAR(64)   NOT NULL,
    production_date   DATE,
    expiry_date       DATE COMMENT '到期日 = 生产日期 + 保质期天数',
    quantity          INT           NOT NULL DEFAULT 0,
    cost_price        DECIMAL(12,2) NOT NULL DEFAULT 0,
    purchase_order_id BIGINT,
    remark            VARCHAR(200),
    created_at        DATETIME      NOT NULL,
    updated_at        DATETIME      NOT NULL,
    deleted           TINYINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_batch_store_no (store_id, batch_no),
    KEY idx_batch_product_expiry (product_id, expiry_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品批次（保质期管理）';

-- 新增权限码：报损（临期/破损下架）
INSERT INTO sys_role_permission (role_code, permission_code)
SELECT 'ADMIN', 'inventory:loss'
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE role_code = 'ADMIN' AND permission_code = 'inventory:loss');
INSERT INTO sys_role_permission (role_code, permission_code)
SELECT 'CASHIER', 'inventory:loss'
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE role_code = 'CASHIER' AND permission_code = 'inventory:loss');

-- 给演示商品补上保质期天数（只影响演示数据，真实商品请在商品管理里维护）
UPDATE product SET shelf_life_days = 365 WHERE shelf_life_days IS NULL AND name LIKE '%农夫山泉%';
UPDATE product SET shelf_life_days = 270 WHERE shelf_life_days IS NULL AND name LIKE '%可乐%';
UPDATE product SET shelf_life_days = 270 WHERE shelf_life_days IS NULL AND name LIKE '%东方树叶%';
UPDATE product SET shelf_life_days = 180 WHERE shelf_life_days IS NULL AND (name LIKE '%乐事%' OR name LIKE '%奥利奥%');
UPDATE product SET shelf_life_days = 120 WHERE shelf_life_days IS NULL AND name LIKE '%沙琪玛%';
