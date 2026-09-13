-- ============================================================================
-- 云小店 生产库表结构（MySQL 8）
-- 与 src/main/resources/db/schema-h2.sql 逻辑一致，差异仅是方言：
--   MySQL：ENGINE/CHARSET、表内联 UNIQUE KEY / KEY、无 IF NOT EXISTS
--   H2   ：CREATE INDEX IF NOT EXISTS（可重复执行，用于本地与 CI）
-- 约定：所有业务表带 store_id（门店隔离）与 deleted（逻辑删除）；金额一律 DECIMAL(12,2)
-- ============================================================================

CREATE TABLE IF NOT EXISTS store (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(64)  NOT NULL,
    code       VARCHAR(32)  NOT NULL,
    address    VARCHAR(200),
    phone      VARCHAR(32),
    status     TINYINT      NOT NULL DEFAULT 1,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_store_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '门店';

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id      BIGINT       NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，任何接口都不返回',
    real_name     VARCHAR(64),
    phone         VARCHAR(32),
    status        TINYINT      NOT NULL DEFAULT 1,
    last_login_at DATETIME,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '员工账号';

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    description VARCHAR(200),
    created_at  DATETIME    NOT NULL,
    updated_at  DATETIME    NOT NULL,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    UNIQUE KEY uk_sys_user_role (user_id, role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户角色关联';

CREATE TABLE IF NOT EXISTS sys_role_permission (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code       VARCHAR(32) NOT NULL,
    permission_code VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_sys_role_permission (role_code, permission_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色权限码';

CREATE TABLE IF NOT EXISTS product_category (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id   BIGINT      NOT NULL,
    name       VARCHAR(64) NOT NULL,
    sort_no    INT         NOT NULL DEFAULT 0,
    status     TINYINT     NOT NULL DEFAULT 1,
    created_at DATETIME    NOT NULL,
    updated_at DATETIME    NOT NULL,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_category_store_name (store_id, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品分类';

CREATE TABLE IF NOT EXISTS product (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id            BIGINT        NOT NULL,
    category_id         BIGINT,
    name                VARCHAR(128)  NOT NULL,
    barcode             VARCHAR(64) COMMENT '门店内唯一，收银台扫码用',
    spec                VARCHAR(64),
    unit                VARCHAR(16),
    purchase_price      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '进价',
    sale_price          DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '售价',
    stock               INT           NOT NULL DEFAULT 0 COMMENT '当前库存，只能由库存服务变更',
    low_stock_threshold INT           NOT NULL DEFAULT 10,
    status              TINYINT       NOT NULL DEFAULT 1 COMMENT '1 在售 / 0 停售',
    version             INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at          DATETIME      NOT NULL,
    updated_at          DATETIME      NOT NULL,
    deleted             TINYINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_product_store_barcode (store_id, barcode),
    KEY idx_product_store_name (store_id, name),
    KEY idx_product_category (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品（含库存）';

CREATE TABLE IF NOT EXISTS inventory_flow (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id     BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL,
    type         VARCHAR(16) NOT NULL COMMENT 'IN 入库 / OUT 出库 / ADJUST 盘点',
    quantity     INT         NOT NULL COMMENT '带符号：入库为正、出库为负',
    before_stock INT         NOT NULL,
    after_stock  INT         NOT NULL,
    ref_type     VARCHAR(16) COMMENT 'PURCHASE / SALE / REFUND / MANUAL',
    ref_no       VARCHAR(64) COMMENT '来源单号',
    remark       VARCHAR(200),
    operator_id  BIGINT,
    created_at   DATETIME    NOT NULL,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    KEY idx_flow_product_created (product_id, created_at),
    KEY idx_flow_store_created (store_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '库存流水（账实相符的依据）';

CREATE TABLE IF NOT EXISTS purchase_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id      BIGINT        NOT NULL,
    order_no      VARCHAR(64)   NOT NULL,
    supplier_name VARCHAR(128),
    item_count    INT           NOT NULL DEFAULT 0,
    total_amount  DECIMAL(12,2) NOT NULL DEFAULT 0,
    status        VARCHAR(16)   NOT NULL COMMENT 'DRAFT / CONFIRMED / CANCELED',
    remark        VARCHAR(200),
    operator_id   BIGINT,
    confirmed_at  DATETIME,
    created_at    DATETIME      NOT NULL,
    updated_at    DATETIME      NOT NULL,
    deleted       TINYINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_purchase_order_no (order_no),
    KEY idx_purchase_store_created (store_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '采购单';

CREATE TABLE IF NOT EXISTS purchase_order_item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT        NOT NULL,
    product_id   BIGINT        NOT NULL,
    product_name VARCHAR(128),
    quantity     INT           NOT NULL,
    unit_cost    DECIMAL(12,2) NOT NULL DEFAULT 0,
    amount       DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at   DATETIME      NOT NULL,
    deleted      TINYINT       NOT NULL DEFAULT 0,
    KEY idx_purchase_item_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '采购明细';

CREATE TABLE IF NOT EXISTS sale_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id        BIGINT        NOT NULL,
    order_no        VARCHAR(64)   NOT NULL,
    request_id      VARCHAR(64)   NOT NULL COMMENT '幂等键：客户端生成，唯一索引保证只落一笔',
    customer_name   VARCHAR(64),
    item_count      INT           NOT NULL DEFAULT 0,
    total_amount    DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '应收',
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    pay_amount      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '实收',
    refund_amount   DECIMAL(12,2) NOT NULL DEFAULT 0,
    pay_method      VARCHAR(16)   NOT NULL COMMENT 'CASH / WECHAT / ALIPAY / CARD',
    status          VARCHAR(24)   NOT NULL COMMENT 'PAID / PARTIAL_REFUNDED / REFUNDED',
    remark          VARCHAR(200),
    operator_id     BIGINT,
    created_at      DATETIME      NOT NULL,
    updated_at      DATETIME      NOT NULL,
    deleted         TINYINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_sale_order_no (order_no),
    UNIQUE KEY uk_sale_request_id (request_id),
    KEY idx_sale_store_created (store_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '销售单（小票）';

CREATE TABLE IF NOT EXISTS sale_order_item (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id          BIGINT        NOT NULL,
    product_id        BIGINT        NOT NULL,
    product_name      VARCHAR(128),
    barcode           VARCHAR(64),
    quantity          INT           NOT NULL,
    refunded_quantity INT           NOT NULL DEFAULT 0 COMMENT '已退数量，退货时条件更新防超退',
    unit_price        DECIMAL(12,2) NOT NULL DEFAULT 0,
    cost_price        DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '下单时冻结的成本价（毛利口径）',
    amount            DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at        DATETIME      NOT NULL,
    deleted           TINYINT       NOT NULL DEFAULT 0,
    KEY idx_sale_item_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '销售明细';

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
    updated_at    DATETIME      NOT NULL,
    UNIQUE KEY uk_summary_store_date (store_id, summary_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '日销售汇总（报表物化）';

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
    created_at  DATETIME    NOT NULL,
    KEY idx_audit_store_created (store_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '审计日志（append-only）';

CREATE TABLE IF NOT EXISTS ai_draft (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id       BIGINT      NOT NULL,
    draft_type     VARCHAR(16) NOT NULL COMMENT 'PURCHASE / SALE',
    raw_text       VARCHAR(1000) COMMENT '用户原话',
    parsed_json    VARCHAR(4000) COMMENT '解析结果，人工确认前的中间态',
    status         VARCHAR(16) NOT NULL COMMENT 'PENDING / CONFIRMED / DISCARDED',
    source         VARCHAR(16) COMMENT 'LLM / RULE',
    created_by     BIGINT,
    confirmed_by   BIGINT,
    created_ref_no VARCHAR(64) COMMENT '确认后生成的单号',
    created_at     DATETIME    NOT NULL,
    updated_at     DATETIME    NOT NULL,
    deleted        TINYINT     NOT NULL DEFAULT 0,
    KEY idx_ai_draft_store_created (store_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 录单草稿';
