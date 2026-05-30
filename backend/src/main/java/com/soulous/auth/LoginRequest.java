package com.soulous.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 【用户登录请求 DTO，使用 Java record 定义，不可变且自动生成 equals/hashCode/toString。
 * 作为服务层的登录入参，不含验证码字段；带验证码的版本见 AuthDtos.LoginWithCaptchaRequest，
 * 前者在 Controller 层转换为本对象后传入 Service 层。】
 *
 * @param username 【用户名，不允许为空或空白字符串】
 * @param password 【明文密码，不允许为空或空白字符串；传输前应由前端进行基础校验】
 */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
