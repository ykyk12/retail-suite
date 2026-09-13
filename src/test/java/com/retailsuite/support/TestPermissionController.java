package com.retailsuite.support;

import com.retailsuite.common.ApiResponse;
import com.retailsuite.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试专用的受保护接口：用来验证"认证与鉴权"这条链路真的生效
 * （收银员无权访问 → 403；管理员 → 200）。放在 test 源码里，不会进生产包。
 */
@RestController
@RequestMapping("/api/test")
public class TestPermissionController {

    @GetMapping("/admin-only")
    @RequiresPermission("user:manage")
    public ApiResponse<String> adminOnly() {
        return ApiResponse.ok("admin-only-ok");
    }

    @GetMapping("/any-login")
    public ApiResponse<String> anyLoginUser() {
        return ApiResponse.ok("any-login-ok");
    }
}
