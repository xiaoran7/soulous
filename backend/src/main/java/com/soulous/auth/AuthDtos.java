package com.soulous.auth;

import jakarta.validation.constraints.NotBlank;

// 【仅 auth 包内部使用的 DTO 定义；跨包使用的 DTO（如 LoginRequest、AuthResponse 等）拆为单独文件，
// 保持公共 API 的清晰性。本文件中的 record 均为 Controller 层入参，经转换后传递给 Service 层。】
// 仅 auth 内部使用的 DTO；跨包使用的 DTO 拆为单独文件

/**
 * 【带验证码的登录请求 DTO，由 Controller 层接收前端提交的登录表单。
 * 包含用户名、密码、验证码 ID 和验证码值四个必填字段。
 * 通过 toService() 方法转换为服务层所需的 LoginRequest 对象，
 * 将验证码校验逻辑与业务逻辑解耦。】
 *
 * @param username    【用户名，不允许为空或空白】
 * @param password    【明文密码，不允许为空或空白】
 * @param captchaId   【验证码 ID，由 /auth/captcha 接口返回，不允许为空】
 * @param captchaCode 【用户输入的验证码值，不允许为空，校验时忽略大小写】
 */
record LoginWithCaptchaRequest(@NotBlank String username, @NotBlank String password,
                                @NotBlank String captchaId, @NotBlank String captchaCode) {
    /**
     * 【将带验证码的登录请求转换为服务层登录请求，剥离验证码字段，
     * 验证码校验在 Controller 层由 CaptchaService 单独处理。】
     *
     * @return 【仅包含 username 和 password 的 LoginRequest 对象】
     */
    LoginRequest toService() { return new LoginRequest(username, password); }
}

/**
 * 【带验证码的注册请求 DTO，由 Controller 层接收前端提交的注册表单。
 * 包含用户名、密码、确认密码、昵称、邮箱、验证码等字段。
 * 确认密码字段仅用于前端一致性校验（前端对比），后端不单独处理；
 * 密码复杂度校验由 PasswordPolicy 在 Service 层完成。】
 *
 * @param username      【用户名，不允许为空或空白，需全局唯一】
 * @param password      【明文密码，不允许为空或空白，需符合 PasswordPolicy 策略】
 * @param confirmPassword 【确认密码，不允许为空，前端需与 password 一致；后端不做二次校验】
 * @param nickname      【用户昵称，可选，用于展示；为空时可默认使用 username】
 * @param email         【邮箱地址，可选，用于找回密码和通知；格式需符合邮箱规范】
 * @param captchaId     【验证码 ID，由 /auth/captcha 接口返回，不允许为空】
 * @param captchaCode   【用户输入的验证码值，不允许为空，校验时忽略大小写】
 */
record RegisterWithCaptchaRequest(@NotBlank String username, @NotBlank String password, @NotBlank String confirmPassword,
                                   String nickname, String email,
                                   @NotBlank String captchaId, @NotBlank String captchaCode) {
    /**
     * 【将带验证码的注册请求转换为服务层注册请求，剥离确认密码和验证码字段。
     * 确认密码的一致性校验应在前端完成，验证码校验在 Controller 层完成。】
     *
     * @return 【仅包含 username、password、nickname、email 的 RegisterRequest 对象】
     */
    RegisterRequest toService() { return new RegisterRequest(username, password, nickname, email); }
}

/**
 * 【修改密码请求 DTO，用于已登录用户修改自己的密码。
 * 需要在请求头中携带 JWT Token 进行身份验证。】
 *
 * @param currentPassword 【当前密码，不允许为空，用于验证用户身份】
 * @param newPassword     【新密码，不允许为空，需符合 PasswordPolicy 策略】
 */
record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
