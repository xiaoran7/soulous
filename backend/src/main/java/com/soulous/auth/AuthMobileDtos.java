package com.soulous.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 移动端认证相关的数据传输对象（DTO）集合。
 * 将登录、注册、刷新令牌的请求/响应 record 集中在同一个文件中，
 * 因为它们都是轻量级的数据结构，仅被 {@link AuthMobileController} 使用。
 *
 * <p>DTOs for {@link AuthMobileController}. Kept in a single file because they are
 * all small request/response shapes used only by the mobile auth endpoints.</p>
 */
/** 移动端登录请求体，username 和 password 均为必填项 */
record MobileLoginRequest(
        /** 用户名，不能为空白字符串 */
        @NotBlank String username,
        /** 用户密码，不能为空白字符串 */
        @NotBlank String password
) {}

/** 移动端注册请求体，包含密码确认字段以防止用户输错密码 */
record MobileRegisterRequest(
        /** 用户名，必填，长度需在 3-32 个字符之间 */
        @NotBlank String username,
        /** 用户密码，必填，需满足密码策略要求 */
        @NotBlank String password,
        /** 确认密码，必填，需与 password 一致 */
        @NotBlank String confirmPassword,
        /** 用户昵称，可选；为空时默认使用 username 作为昵称 */
        String nickname
) {}

/** 移动端刷新令牌请求体 */
record MobileRefreshRequest(
        /** 上一次登录/注册时颁发的原始刷新令牌（raw token），必填 */
        @NotBlank String refreshToken
) {}

/**
 * 登录、注册、刷新接口的统一响应体。
 * 客户端收到后应将 refreshToken 安全存储（如 Keychain / Keystore），
 * 并在 accessToken 过期前主动调用 /refresh 接口轮换令牌，以减少 401 错误。
 *
 * <p>Response for login / register / refresh. {@code accessExpiresIn} is the JWT
 * lifetime in seconds — the client can use it to schedule a pre-emptive refresh
 * a bit before the token actually expires (reduces 401 churn).</p>
 */
record MobileAuthResponse(
        /** 短期访问令牌（JWT），用于后续 API 请求的身份认证 */
        String accessToken,
        /** 长期刷新令牌，用于轮换新的访问令牌；为一次性令牌，使用后立即失效 */
        String refreshToken,
        /** 访问令牌的过期时间（秒），客户端可用于在过期前提前刷新 */
        long accessExpiresIn,
        /** 用户信息对象（包含 id、username、nickname、email、avatarUrl、role 等字段） */
        Object user
) {}
