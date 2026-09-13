-- ============================================================================
-- 云小店 核心表结构（H2 / MODE=MySQL，用于本地零依赖运行与自动化测试）
-- 与 deploy/mysql/init/01-schema.sql 逻辑一致，仅方言差异：
--   H2   ：CREATE INDEX IF NOT EXISTS（可重复执行）
--   MySQL：CREATE TABLE 内联 KEY / UNIQUE KEY + ENGINE/CHARSET
-- 约定：
--   1) 所有业务表带 store_id（门店隔离）与 deleted（逻辑删除，MyBatis-Plus 自动过滤）
--   2) 金额统一 DECIMAL(12,2)，绝不用 double（浮点误差在收银场景是灾难）
--   3) 单号、幂等键、条码等有业务唯一性的列都建唯一索引，由数据库兜底
-- ============================================================================

CREATE TABLE IF NOT EXISTS store (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    code        VARCHAR(32)  NOT NULL,
    address     VARCHAR(200),
    phone       VARCHAR(32),
    status      TINYINT      NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    deleted     TINYINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_store_code ON store (code);

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id      BIGINT       NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    real_name     VARCHAR(64),
    phone         VARCHAR(32),
    status        TINYINT      NOT NULL DEFAULT 1,
    last_login_at DATETIME,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    deleted       TINYINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_username ON sys_user (username);

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    description VARCHAR(200),
    created_at  DATETIME    NOT NULL,
    updated_at  DATETIME    NOT NULL,
    deleted     TINYINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_code ON sys_role (code);

CREATE TABLE IF NOT EXISTS sys_user_role (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_role ON sys_user_role (user_id, role_id);

CREATE TABLE IF NOT EXISTS sys_role_permission (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code       VARCHAR(32) NOT NULL,
    permission_code VARCHAR(64) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_permission ON sys_role_permission (role_code, permission_code);

CREATE TABLE IF NOT EXISTS product_category (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id   BIGINT      NOT NULL,
    name       VARCHAR(64) NOT NULL,
    sort_no    INT         NOT NULL DEFAULT 0,
    status     TINYINT     NOT NULL DEFAULT 1,
    created_at DATETIME    NOT NULL,
    updated_at DATETIME    NOT NULL,
    deleted    TINYINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_category_store_name ON product_category (store_id, name);

CREATE TABLE IF NOT EXISTS product (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id            BIGINT        NOT NULL,
    category_id         BIGINT,
    name                VARCHAR(128)  NOT NULL,
    barcode             VARCHAR(64),
    spec                VARCHAR(64),
    unit                VARCHAR(16),
    purchase_price      DECIMAL(12,2) NOT NULL DEFAULT 0,
    sale_price          DECIMAL(12,2) NOT NULL DEFAULT 0,
    stock               INT           NOT NULL DEFAULT 0,
    low_stock_threshold INT           NOT NULL DEFAULT 10,
    -- 保质期天数：NULL 表示不追踪保质期（如日用品）；有值时入库必须登记生产日期
    shelf_life_days     INT,
    status              TINYINT       NOT NULL DEFAULT 1,
    version             INT           NOT NULL DEFAULT 0,
    created_at          DATETIME      NOT NULL,
    updated_at          DATETIME      NOT NULL,
    deleted             TINYINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_product_store_barcode ON product (store_id, barcode);
CREATE INDEX IF NOT EXISTS idx_product_store_name ON product (store_id, name);
CREATE INDEX IF NOT EXISTS idx_product_category ON product (category_id);

CREATE TABLE IF NOT EXISTS inventory_flow (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id     BIGINT       NOT NULL,
    product_id   BIGINT       NOT NULL,
    type         VARCHAR(16)  NOT NULL,
    quantity     INT          NOT NULL,
    before_stock INT          NOT NULL,
    after_stock  INT          NOT NULL,
    ref_type     VARCHAR(16),
    ref_no       VARCHAR(64),
    remark       VARCHAR(200),
    -- 关联批次：出库/报损能追到具体是哪一批货（有批次管理时必填）
    batch_id     BIGINT,
    operator_id  BIGINT,
    created_at   DATETIME     NOT NULL,
    deleted      TINYINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_flow_product_created ON inventory_flow (product_id, created_at);
CREATE INDEX IF NOT EXISTS idx_flow_store_created ON inventory_flow (store_id, created_at);

CREATE TABLE IF NOT EXISTS purchase_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id      BIGINT        NOT NULL,
    order_no      VARCHAR(64)   NOT NULL,
    supplier_name VARCHAR(128),
    item_count    INT           NOT NULL DEFAULT 0,
    total_amount  DECIMAL(12,2) NOT NULL DEFAULT 0,
    status        VARCHAR(16)   NOT NULL,
    remark        VARCHAR(200),
    operator_id   BIGINT,
    confirmed_at  DATETIME,
    created_at    DATETIME      NOT NULL,
    updated_at    DATETIME      NOT NULL,
    deleted       TINYINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_purchase_order_no ON purchase_order (order_no);
CREATE INDEX IF NOT EXISTS idx_purchase_store_created ON purchase_order (store_id, created_at);

CREATE TABLE IF NOT EXISTS purchase_order_item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT        NOT NULL,
    product_id   BIGINT        NOT NULL,
    product_name VARCHAR(128),
    quantity     INT           NOT NULL,
    unit_cost    DECIMAL(12,2) NOT NULL DEFAULT 0,
    amount       DECIMAL(12,2) NOT NULL DEFAULT 0,
    -- 进货批次信息：生产日期与保质期（保质期为空则用商品档案上的保质期天数推算到期日）
    production_date DATE,
    shelf_life_days INT,
    created_at   DATETIME      NOT NULL,
    deleted      TINYINT       NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_purchase_item_order ON purchase_order_item (order_id);

CREATE TABLE IF NOT EXISTS sale_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id        BIGINT        NOT NULL,
    order_no        VARCHAR(64)   NOT NULL,
    request_id      VARCHAR(64)   NOT NULL,
    customer_name   VARCHAR(64),
    item_count      INT           NOT NULL DEFAULT 0,
    total_amount    DECIMAL(12,2) NOT NULL DEFAULT 0,
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    pay_amount      DECIMAL(12,2) NOT NULL DEFAULT 0,
    refund_amount   DECIMAL(12,2) NOT NULL DEFAULT 0,
    pay_method      VARCHAR(16)   NOT NULL,
    status          VARCHAR(24)   NOT NULL,
    remark          VARCHAR(200),
    operator_id     BIGINT,
    created_at      DATETIME      NOT NULL,
    updated_at      DATETIME      NOT NULL,
    deleted         TINYINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sale_order_no ON sale_order (order_no);
-- 幂等的落点：同一个收银请求（request_id）在库层面只能产生一笔订单，重复提交会撞唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_sale_request_id ON sale_order (request_id);
CREATE INDEX IF NOT EXISTS idx_sale_store_created ON sale_order (store_id, created_at);

CREATE TABLE IF NOT EXISTS sale_order_item (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id          BIGINT        NOT NULL,
    product_id        BIGINT        NOT NULL,
    product_name      VARCHAR(128),
    barcode           VARCHAR(64),
    quantity          INT           NOT NULL,
    refunded_quantity INT           NOT NULL DEFAULT 0,
    unit_price        DECIMAL(12,2) NOT NULL DEFAULT 0,
    -- 冻结下单当时的成本价：毛利要用"当时的成本"算，而不是商品今天的进价（后者会随进货价波动而失真）
    cost_price        DECIMAL(12,2) NOT NULL DEFAULT 0,
    amount            DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at        DATETIME      NOT NULL,
    deleted           TINYINT       NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sale_item_order ON sale_order_item (order_id);

CREATE TABLE IF NOT EXISTS daily_sales_summary (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id      BIGINT        NOT NULL,
    summary_date  DATE          NOT NULL,
    order_count   INT           NOT NULL DEFAULT 0,
    item_count    INT           NOT NULL DEFAULT 0,
    sales_amount  DECIMAL(12,2) NOT NULL DEFAULT 0,
    refund_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    net_amount    DECIMAL(12,2) NOT NULL DEFAULT 0,
    gross_profit  DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL,
    updated_at    DATETIME      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_summary_store_date ON daily_sales_summary (store_id, summary_date);

CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id    BIGINT      NOT NULL,
    user_id     BIGINT,
    username    VARCHAR(64),
    action      VARCHAR(64) NOT NULL,
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    detail      VARCHAR(500),
    ip          VARCHAR(64),
    created_at  DATETIME    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_store_created ON audit_log (store_id, created_at);

CREATE TABLE IF NOT EXISTS ai_draft (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id       BIGINT      NOT NULL,
    draft_type     VARCHAR(16) NOT NULL,
    raw_text       VARCHAR(1000),
    parsed_json    VARCHAR(4000),
    status         VARCHAR(16) NOT NULL,
    -- 解析来源：LLM 或 RULE（本地规则），用来对比两种解析方式的准确率
    source         VARCHAR(16),
    created_by     BIGINT,
    confirmed_by   BIGINT,
    created_ref_no VARCHAR(64),
    created_at     DATETIME    NOT NULL,
    updated_at     DATETIME    NOT NULL,
    deleted        TINYINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ai_draft_store_created ON ai_draft (store_id, created_at);

-- ============================================================================
-- 批次与保质期（M1）：食品/生鲜门店必须按批次管理到期日，出库按先进先出（FIFO）
-- 设计要点：
--   1) product.stock 仍是聚合库存（收银扣减走条件更新，性能最好）；
--      product_batch 记录每一批的剩余数量与到期日，两者由库存服务在同一事务内同步；
--   2) 出库按"最早到期优先"扣批次（近效期先出，减少报损），与纯 FIFO 按入库时间相比更符合食品零售；
--   3) 到期日 = 生产日期 + 保质期天数；未登记生产日期的批次 expiry_date 为空（如日用品）；
--   4) 一致性由对账接口校验：Σ批次数量 必须等于 product.stock。
-- ============================================================================
CREATE TABLE IF NOT EXISTS product_batch (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id        BIGINT        NOT NULL,
    product_id      BIGINT        NOT NULL,
    batch_no        VARCHAR(64)   NOT NULL,
    production_date DATE,
    expiry_date     DATE,
    quantity        INT           NOT NULL DEFAULT 0,
    cost_price      DECIMAL(12,2) NOT NULL DEFAULT 0,
    purchase_order_id BIGINT,
    remark          VARCHAR(200),
    created_at      DATETIME      NOT NULL,
    updated_at      DATETIME      NOT NULL,
    deleted         TINYINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_batch_store_no ON product_batch (store_id, batch_no);
CREATE INDEX IF NOT EXISTS idx_batch_product_expiry ON product_batch (product_id, expiry_date);

-- ============================================================================
-- 幂等升级语句：让已有的本地 H2 文件库也能升上来（新建库时这些语句是无操作）
-- MySQL 侧见 deploy/mysql/migration/（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）
-- ============================================================================
ALTER TABLE product ADD COLUMN IF NOT EXISTS shelf_life_days INT;
ALTER TABLE inventory_flow ADD COLUMN IF NOT EXISTS batch_id BIGINT;
ALTER TABLE purchase_order_item ADD COLUMN IF NOT EXISTS production_date DATE;
ALTER TABLE purchase_order_item ADD COLUMN IF NOT EXISTS shelf_life_days INT;
