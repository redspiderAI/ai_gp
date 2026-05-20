package com.aigp.demo.web.security;

import io.swagger.v3.oas.annotations.Parameter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在控制器方法参数上，注入当前访问令牌对应用户（由 {@link CurrentUserArgumentResolver} 解析）。
 * 该参数由服务端从 JWT 解析，**不是**客户端请求参数；OpenAPI 中隐藏以免误解。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Parameter(hidden = true)
public @interface CurrentUser {}
