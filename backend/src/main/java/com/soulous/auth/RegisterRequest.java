package com.soulous.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 【用户注册请求 DTO（Java Record），用于接收 POST /api/auth/register 的请求体。
 *  Record 天然不可变且自带 equals/hashCode/toString，适合作为请求/响应数据载体。
 *  字段通过 Jakarta Validation 注解进行入参校验，校验失败时由全局异常处理器返回 400 响应。】
 *
 * <p>Registration request payload.</p>
 *
 * @param username 【用户名，必填（@NotBlank），长度不超过 64 字符，全局唯一】
 * @param password 【密码，必填（@NotBlank），建议前端做最低强度校验（如 8 位以上）】
 * @param nickname 【昵称，可选。未提供时服务端可默认使用 username】
 * @param email    【邮箱，可选。用于后续通知和密码找回功能】
 */
public record RegisterRequest(@NotBlank String username, @NotBlank String password, String nickname, String email) {}
