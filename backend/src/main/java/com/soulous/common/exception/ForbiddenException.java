package com.soulous.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 【禁止访问异常 —— 当已认证用户尝试访问其无权限的资源时抛出。
 * 例如：普通用户尝试访问管理员接口、用户尝试操作他人的数据等。
 * 通过 @ResponseStatus 注解自动映射为 HTTP 403 响应。】
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ForbiddenException extends RuntimeException {
    /**
     * 【构造禁止访问异常】
     *
     * @param message 【异常的可读描述信息】
     */
    public ForbiddenException(String message) { super(message); }
}
