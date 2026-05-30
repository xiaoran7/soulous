package com.soulous.auth;

/**
 * 【认证响应 DTO，用于登录、注册成功后返回给前端。
 * 包含 JWT Token 和用户信息，前端收到后应将 Token 存储到 localStorage 或 Cookie 中，
 * 后续请求通过 Authorization 请求头携带此 Token 进行身份验证。】
 *
 * @param token 【JWT Token 字符串，包含用户 ID 和角色声明，有效期由后端配置决定】
 * @param user  【用户信息对象，通常为 Map 或 UserResponse DTO，包含 id、username、nickname、role 等字段；
                使用 Object 类型以兼容不同的用户信息结构】
 */
public record AuthResponse(String token, Object user) {}
