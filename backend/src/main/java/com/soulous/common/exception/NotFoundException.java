package com.soulous.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 【资源未找到异常 —— 当请求访问的资源（如用户、宠物、任务等）在数据库中不存在时抛出。
 * 通过 @ResponseStatus 注解自动映射为 HTTP 404 响应，
 * 避免在每个 Controller 方法中手动检查资源是否存在。】
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {
    /**
     * 【构造资源未找到异常】
     *
     * @param message 【异常的可读描述信息，通常包含未找到资源的标识】
     */
    public NotFoundException(String message) { super(message); }
}
