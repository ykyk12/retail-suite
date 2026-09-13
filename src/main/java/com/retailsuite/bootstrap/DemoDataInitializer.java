package com.retailsuite.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.entity.ProductCategory;
import com.retailsuite.product.mapper.ProductCategoryMapper;
import com.retailsuite.product.mapper.ProductMapper;
import com.retailsuite.product.service.ProductService;
import com.retailsuite.store.entity.Store;
import com.retailsuite.store.mapper.StoreMapper;
import com.retailsuite.user.entity.SysRole;
import com.retailsuite.user.entity.SysUser;
import com.retailsuite.user.entity.SysUserRole;
import com.retailsuite.user.mapper.SysRoleMapper;
import com.retailsuite.user.mapper.SysUserMapper;
import com.retailsuite.user.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示数据初始化（**幂等、不破坏已有数据**）。
 *
 * 为什么放在 Java 而不是 SQL：
 * 1) 密码哈希必须运行时生成（BCrypt 盐随机），写死在 SQL 里会导致换机器登录不上；
 * 2) **原来的 SQL 种子脚本带 DELETE**，而 Spring 的 sql.init 会在每次新建上下文时重跑，
 *    在测试里会把上一个上下文写入的商品与库存流水删掉、只留下订单，制造出"有销售、无流水"的假差异——
 *    这个问题在 CI 上真实暴露过（验收测试的对账断言失败），根因就是破坏性种子；
 * 3) 幂等初始化可以反复执行：存在就跳过，不存在才创建，因此开发、测试、首次部署都安全。
 *
 * 部署到 MySQL 时的演示数据仍由 deploy/mysql/init/02-seed.sql 负责（容器首次初始化执行一次）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private static final String STORE_CODE = "DEMO001";

    /** 角色 → 权限码。收银员刻意不给进货、库存调整、用户管理权限（最小权限原则）。 */
    private static final Map<String, List<String>> ROLE_PERMISSIONS = new LinkedHashMap<>();

    static {
        ROLE_PERMISSIONS.put("ADMIN", List.of(
                "product:read", "product:write", "category:write",
                "inventory:read", "inventory:adjust",
                "purchase:read", "purchase:write",
                "sale:create", "sale:read", "refund:create",
                "report:read", "user:manage", "audit:read", "ai:use"));
        ROLE_PERMISSIONS.put("CASHIER", List.of(
                "product:read", "inventory:read",
                "sale:create", "sale:read", "refund:create",
                "report:read", "ai:use"));
    }

    /** 演示商品（生鲜除外，条码为真实 EAN-13 形态，便于扫码枪测试）。 */
    private record DemoProduct(String name, String category, String barcode, String spec, String unit,
                               String purchasePrice, String salePrice, int stock, int threshold) {
    }

    private static final List<DemoProduct> DEMO_PRODUCTS = List.of(
            new DemoProduct("农夫山泉 550ml", "饮料", "6921168509256", "550ml", "瓶", "1.20", "2.00", 120, 20),
            new DemoProduct("可口可乐 330ml", "饮料", "6928804011153", "330ml", "罐", "2.30", "3.50", 80, 20),
            new DemoProduct("东方树叶 500ml", "饮料", "6921168594849", "500ml", "瓶", "3.50", "5.00", 40, 10),
            new DemoProduct("乐事薯片 原味 70g", "零食", "6924743915848", "70g", "袋", "4.20", "6.50", 35, 10),
            new DemoProduct("奥利奥饼干 116g", "零食", "6901668005628", "116g", "盒", "5.80", "8.50", 25, 10),
            // 下面两个刻意低库存：用于演示「库存预警」与「一句话补货」
            new DemoProduct("徐福记沙琪玛", "零食", "6901285991219", "160g", "包", "6.00", "9.00", 8, 10),
            new DemoProduct("抽纸 三层 120抽", "日用", "6922255451427", "120抽", "包", "3.00", "4.50", 60, 15),
            new DemoProduct("一次性纸杯 50只", "日用", "6934567890125", "50只", "提", "5.00", "8.00", 4, 10));

    private final StoreMapper storeMapper;
    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper productCategoryMapper;
    private final ProductService productService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        Store store = ensureStore();
        ensureRolesAndPermissions();
        ensureCategories(store.getId());
        ensureDemoProducts(store.getId());
        ensureUser(store.getId(), "admin", "admin123", "张店长", "ADMIN");
        ensureUser(store.getId(), "cashier", "cashier123", "小李（收银员）", "CASHIER");

        log.info("演示数据就绪（幂等）：门店={}（id={}），商品 {} 个",
                store.getName(), store.getId(), DEMO_PRODUCTS.size());
        log.info("演示账号：admin/admin123（管理员）、cashier/cashier123（收银员，无进货与库存调整权限）");
        log.info("接口文档：http://localhost:8080/swagger-ui.html ，健康检查：/actuator/health");
    }

    private Store ensureStore() {
        Store existing = storeMapper.selectOne(new LambdaQueryWrapper<Store>()
                .eq(Store::getCode, STORE_CODE).last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        Store store = new Store();
        store.setName("云小店（演示店）");
        store.setCode(STORE_CODE);
        store.setAddress("成都市新都区示例路 1 号");
        store.setPhone("028-88888888");
        store.setStatus(1);
        store.setCreatedAt(LocalDateTime.now());
        store.setUpdatedAt(LocalDateTime.now());
        store.setDeleted(0);
        storeMapper.insert(store);
        log.info("已创建演示门店：{}（id={}）", store.getName(), store.getId());
        return store;
    }

    private void ensureRolesAndPermissions() {
        for (Map.Entry<String, List<String>> entry : ROLE_PERMISSIONS.entrySet()) {
            String roleCode = entry.getKey();
            if (sysRoleMapper.selectIdByCode(roleCode) == null) {
                SysRole role = new SysRole();
                role.setCode(roleCode);
                role.setName("ADMIN".equals(roleCode) ? "店长/管理员" : "收银员");
                role.setDescription("ADMIN".equals(roleCode) ? "全部权限" : "收银、退货、查库存与报表");
                role.setCreatedAt(LocalDateTime.now());
                role.setUpdatedAt(LocalDateTime.now());
                role.setDeleted(0);
                sysRoleMapper.insert(role);
                log.info("已创建角色：{}", roleCode);
            }
            for (String permission : entry.getValue()) {
                if (sysRoleMapper.countPermission(roleCode, permission) == 0) {
                    sysRoleMapper.insertPermission(roleCode, permission);
                }
            }
        }
    }

    private void ensureCategories(Long storeId) {
        for (String name : List.of("饮料", "零食", "日用")) {
            long exists = productCategoryMapper.selectCount(new LambdaQueryWrapper<ProductCategory>()
                    .eq(ProductCategory::getStoreId, storeId)
                    .eq(ProductCategory::getName, name));
            if (exists == 0) {
                int sortNo = List.of("饮料", "零食", "日用").indexOf(name) + 1;
                productService.createCategory(storeId, new ProductDtos.CategoryRequest(name, sortNo));
            }
        }
    }

    /** 只在门店完全没有商品时灌演示商品：任何已有数据都不动（幂等且非破坏性）。 */
    private void ensureDemoProducts(Long storeId) {
        long existing = productMapper.selectCount(new LambdaQueryWrapper<com.retailsuite.product.entity.Product>()
                .eq(com.retailsuite.product.entity.Product::getStoreId, storeId));
        if (existing > 0) {
            log.debug("门店 {} 已有 {} 个商品，跳过演示商品初始化", storeId, existing);
            return;
        }
        Map<String, Long> categoryIds = new LinkedHashMap<>();
        for (ProductCategory category : productCategoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getStoreId, storeId))) {
            categoryIds.put(category.getName(), category.getId());
        }
        for (DemoProduct demo : DEMO_PRODUCTS) {
            productService.create(storeId, new ProductDtos.CreateRequest(
                    demo.name(), categoryIds.get(demo.category()), demo.barcode(), demo.spec(), demo.unit(),
                    new BigDecimal(demo.purchasePrice()), new BigDecimal(demo.salePrice()),
                    demo.stock(), demo.threshold()));
        }
        log.info("已写入 {} 个演示商品（含期初库存流水）", DEMO_PRODUCTS.size());
    }

    private void ensureUser(Long storeId, String username, String rawPassword, String realName, String roleCode) {
        SysUser existing = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        Long userId;
        if (existing == null) {
            SysUser user = new SysUser();
            user.setStoreId(storeId);
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setRealName(realName);
            user.setStatus(1);
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            user.setDeleted(0);
            sysUserMapper.insert(user);
            userId = user.getId();
            log.info("已创建演示用户 {}", username);
        } else {
            userId = existing.getId();
        }

        Long roleId = sysRoleMapper.selectIdByCode(roleCode);
        if (roleId == null) {
            log.warn("角色 {} 不存在，用户 {} 未绑定角色", roleCode, username);
            return;
        }
        if (sysUserRoleMapper.countByUserAndRole(userId, roleId) == 0) {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(userId);
            relation.setRoleId(roleId);
            sysUserRoleMapper.insert(relation);
            log.info("已为用户 {} 绑定角色 {}", username, roleCode);
        }
    }
}
