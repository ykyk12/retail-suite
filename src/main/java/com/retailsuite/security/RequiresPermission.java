package com.retailsuite.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口所需权限码（形如 {@code product:write}）。
 * 写在 Controller 方法或类上，由 AuthInterceptor 校验——权限声明贴着接口，读代码时不用去别处找配置。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 需要的权限码。 */
    String value();
}
