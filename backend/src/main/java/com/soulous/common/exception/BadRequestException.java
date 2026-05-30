package com.soulous.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 【请求参数错误异常 —— 当客户端发送的请求参数不合法或缺失必要字段时抛出。
 * 例如：参数格式错误、业务规则校验失败等。
 * 通过 @ResponseStatus 注解自动映射为 HTTP 400 响应。】
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BadRequestException extends RuntimeException {
    /**
     * 【构造请求参数错误异常】
     *
     * @param message 【异常的可读描述信息，通常说明具体哪个参数有问题】
     */
    public BadRequestException(String message) { super(message); }
}
