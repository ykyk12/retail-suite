-- ============================================================================
-- 演示数据（方言中立，H2 本地开发/测试与 MySQL 部署都可直接用；可重复执行）
--
-- 两个刻意的写法：
-- 1) 不写死主键：显式插入主键不会推进 H2 的自增序列，之后新增商品会撞主键；
--    这里全部交给自增，关联关系用子查询定位（MySQL 也允许引用其它表做子查询）。
-- 2) 不写死密码哈希：管理员/收银员账号由 DemoDataInitializer 启动时用 BCrypt 生成，
--    因为同一密码在不同机器上的哈希不同，写死会导致"SQL 灌了却登录不上"。
-- ============================================================================

DELETE FROM sys_role_permission;
DELETE FROM sys_role;
DELETE FROM sys_user_role;
DELETE FROM inventory_flow;
DELETE FROM product;
DELETE FROM product_category;
DELETE FROM store;

INSERT INTO store (name, code, address, phone, status, created_at, updated_at, deleted)
VALUES ('云小店（演示店）', 'DEMO001', '成都市新都区示例路 1 号', '028-88888888', 1, NOW(), NOW(), 0);

INSERT INTO sys_role (code, name, description, created_at, updated_at, deleted) VALUES
  ('ADMIN',   '店长/管理员', '全部权限', NOW(), NOW(), 0),
  ('CASHIER', '收银员',      '收银、退货、查库存与报表', NOW(), NOW(), 0);

-- 权限码：模块:动作。收银员刻意不给用户管理、库存调整、采购写权限（最小权限原则）
-- inventory:loss 是报损（临期/破损下架）：收银员在货架前端发现临期品，允许其报损
INSERT INTO sys_role_permission (role_code, permission_code) VALUES
  ('ADMIN', 'product:read'), ('ADMIN', 'product:write'), ('ADMIN', 'category:write'),
  ('ADMIN', 'inventory:read'), ('ADMIN', 'inventory:adjust'), ('ADMIN', 'inventory:loss'),
  ('ADMIN', 'purchase:read'), ('ADMIN', 'purchase:write'),
  ('ADMIN', 'sale:create'), ('ADMIN', 'sale:read'), ('ADMIN', 'refund:create'),
  ('ADMIN', 'report:read'), ('ADMIN', 'user:manage'), ('ADMIN', 'audit:read'), ('ADMIN', 'ai:use'),
  ('CASHIER', 'product:read'),
  ('CASHIER', 'inventory:read'), ('CASHIER', 'inventory:loss'),
  ('CASHIER', 'sale:create'), ('CASHIER', 'sale:read'), ('CASHIER', 'refund:create'),
  ('CASHIER', 'report:read'), ('CASHIER', 'ai:use');

INSERT INTO product_category (store_id, name, sort_no, status, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'), '饮料', 1, 1, NOW(), NOW(), 0);
INSERT INTO product_category (store_id, name, sort_no, status, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'), '零食', 2, 1, NOW(), NOW(), 0);
INSERT INTO product_category (store_id, name, sort_no, status, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'), '日用', 3, 1, NOW(), NOW(), 0);

-- 商品（含两个低库存商品，用于演示库存预警与"自然语言录入采购单"）
INSERT INTO product (store_id, category_id, name, barcode, spec, unit, purchase_price, sale_price,
                     stock, low_stock_threshold, status, version, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'),
        (SELECT id FROM product_category WHERE store_id = (SELECT id FROM store WHERE code = 'DEMO001') AND name = '饮料'),
        '农夫山泉 550ml', '6921168509256', '550ml', '瓶', 1.20, 2.00, 120, 20, 1, 0, NOW(), NOW(), 0);
INSERT INTO product (store_id, category_id, name, barcode, spec, unit, purchase_price, sale_price,
                     stock, low_stock_threshold, status, version, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'),
        (SELECT id FROM product_category WHERE store_id = (SELECT id FROM store WHERE code = 'DEMO001') AND name = '饮料'),
        '可口可乐 330ml', '6928804011153', '330ml', '罐', 2.30, 3.50, 80, 20, 1, 0, NOW(), NOW(), 0);
INSERT INTO product (store_id, category_id, name, barcode, spec, unit, purchase_price, sale_price,
                     stock, low_stock_threshold, status, version, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'),
        (SELECT id FROM product_category WHERE store_id = (SELECT id FROM store WHERE code = 'DEMO001') AND name = '饮料'),
        '东方树叶 500ml', '6921168594849', '500ml', '瓶', 3.50, 5.00, 40, 10, 1, 0, NOW(), NOW(), 0);
INSERT INTO product (store_id, category_id, name, barcode, spec, unit, purchase_price, sale_price,
                     stock, low_stock_threshold, status, version, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'),
        (SELECT id FROM product_category WHERE store_id = (SELECT id FROM store WHERE code = 'DEMO001') AND name = '零食'),
        '乐事薯片 原味 70g', '6924743915848', '70g', '袋', 4.20, 6.50, 35, 10, 1, 0, NOW(), NOW(), 0);
INSERT INTO product (store_id, category_id, name, barcode, spec, unit, purchase_price, sale_price,
                     stock, low_stock_threshold, status, version, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'),
        (SELECT id FROM product_category WHERE store_id = (SELECT id FROM store WHERE code = 'DEMO001') AND name = '零食'),
        '奥利奥饼干 116g', '6901668005628', '116g', '盒', 5.80, 8.50, 25, 10, 1, 0, NOW(), NOW(), 0);
INSERT INTO product (store_id, category_id, name, barcode, spec, unit, purchase_price, sale_price,
                     stock, low_stock_threshold, status, version, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'),
        (SELECT id FROM product_category WHERE store_id = (SELECT id FROM store WHERE code = 'DEMO001') AND name = '零食'),
        '徐福记沙琪玛', '6901285991219', '160g', '包', 6.00, 9.00, 8, 10, 1, 0, NOW(), NOW(), 0);
INSERT INTO product (store_id, category_id, name, barcode, spec, unit, purchase_price, sale_price,
                     stock, low_stock_threshold, status, version, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'),
        (SELECT id FROM product_category WHERE store_id = (SELECT id FROM store WHERE code = 'DEMO001') AND name = '日用'),
        '抽纸 三层 120抽', '6922255451427', '120抽', '包', 3.00, 4.50, 60, 15, 1, 0, NOW(), NOW(), 0);
INSERT INTO product (store_id, category_id, name, barcode, spec, unit, purchase_price, sale_price,
                     stock, low_stock_threshold, status, version, created_at, updated_at, deleted)
VALUES ((SELECT id FROM store WHERE code = 'DEMO001'),
        (SELECT id FROM product_category WHERE store_id = (SELECT id FROM store WHERE code = 'DEMO001') AND name = '日用'),
        '一次性纸杯 50只', '6934567890125', '50只', '提', 5.00, 8.00, 4, 10, 1, 0, NOW(), NOW(), 0);

-- 期初库存流水：说明"库存不是凭空来的"，每一次变动都有账可查
INSERT INTO inventory_flow (store_id, product_id, type, quantity, before_stock, after_stock,
                            ref_type, ref_no, remark, operator_id, created_at, deleted)
SELECT p.store_id, p.id, 'IN', p.stock, 0, p.stock, 'MANUAL', NULL, '期初建账', NULL, NOW(), 0
FROM product p;

-- 保质期天数（饮料/零食追踪到期日，日用品不追踪）。
-- 说明：这里只设置商品档案上的保质期天数；由于期初库存是 SQL 直接写入的（没有走库存服务），
-- 它还不会生成批次。首次部署后请对这类商品做一次「盘点调整」或确认一张「采购单」，
-- 库存才会落到具体批次上，随后可用 GET /api/inventory/batch-mismatch 核对是否一致。
UPDATE product SET shelf_life_days = 365 WHERE shelf_life_days IS NULL AND name LIKE '%农夫山泉%';
UPDATE product SET shelf_life_days = 270 WHERE shelf_life_days IS NULL AND name LIKE '%可乐%';
UPDATE product SET shelf_life_days = 270 WHERE shelf_life_days IS NULL AND name LIKE '%东方树叶%';
UPDATE product SET shelf_life_days = 180 WHERE shelf_life_days IS NULL AND (name LIKE '%乐事%' OR name LIKE '%奥利奥%');
UPDATE product SET shelf_life_days = 120 WHERE shelf_life_days IS NULL AND name LIKE '%沙琪玛%';
