package com.soulous.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 【管理员创建用户请求 DTO，仅限具有管理员角色的用户使用。
 * 管理员可通过此接口直接创建用户，无需验证码，且可指定用户角色。
 * 与普通注册流程不同，管理员创建的用户通常无需邮箱验证等步骤。】
 *
 * @param username 【用户名，不允许为空或空白，需全局唯一，用于登录】
 * @param password 【初始密码，不允许为空或空白，需符合 PasswordPolicy 策略】
 * @param nickname 【用户昵称，可选，用于界面展示；为空时可默认使用 username】
 * @param role     【用户角色，可选，如 "admin"、"user" 等；为空时使用系统默认角色 "user"】
 */
public record AdminCreateUserRequest(@NotBlank String username, @NotBlank String password, String nickname, String role) {}
