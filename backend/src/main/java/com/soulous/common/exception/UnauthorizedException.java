package com.soulous.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 【未认证异常 —— 当请求未携带有效的认证凭据（如 JWT 令牌缺失或无效）时抛出。
 * 通过 @ResponseStatus 注解自动映射为 HTTP 401 响应，
 * 由 Spring MVC 框架在异常传播到 DispatcherServlet 时自动处理。】
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class UnauthorizedException extends RuntimeException {
    /**
     * 【构造未认证异常】
     *
     * @param message 【异常的可读描述信息】
     */
    public UnauthorizedException(String message) { super(message); }
}
