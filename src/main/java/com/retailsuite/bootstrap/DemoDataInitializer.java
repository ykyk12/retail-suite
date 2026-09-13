package com.retailsuite.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.retailsuite.store.entity.Store;
import com.retailsuite.store.mapper.StoreMapper;
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

import java.time.LocalDateTime;

/**
 * 首次启动初始化账号。
 *
 * 为什么不在 SQL 里写密码：BCrypt 每次生成的盐不同，把某台机器上的哈希写进仓库，
 * 在别的机器上要么对不上、要么让人误以为"改过密码却不生效"。所以初始化逻辑放在代码里，
 * 只在用户不存在时创建，已存在则不动（重复启动安全）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private final StoreMapper storeMapper;
    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>().last("LIMIT 1"));
        if (store == null) {
            log.warn("未找到门店记录，跳过账号初始化（请检查 db/data-demo.sql 是否执行）");
            return;
        }
        ensureUser(store.getId(), "admin", "admin123", "张店长", "ADMIN");
        ensureUser(store.getId(), "cashier", "cashier123", "小李（收银员）", "CASHIER");
        log.info("演示账号就绪：admin/admin123（管理员）、cashier/cashier123（收银员）");
        log.info("接口文档：http://localhost:8080/swagger-ui.html ，健康检查：/actuator/health");
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
            log.warn("角色 {} 不存在，用户 {} 未绑定角色（请检查 db/data-demo.sql）", roleCode, username);
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
