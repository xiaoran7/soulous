package com.soulous.common.web;

import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.ModerationBlockedException;
import com.soulous.common.exception.TooManyRequestsException;
import com.soulous.common.exception.UnauthorizedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 【全局 REST API 异常处理器：统一捕获各类业务异常和框架异常，
 * 将其转换为结构化的 JSON 错误响应返回给前端。
 *
 * 处理的异常类型：
 * - BadRequestException → 400 请求参数错误
 * - ModerationBlockedException → 422 内容审核拦截
 * - UnauthorizedException → 401 未授权
 * - TooManyRequestsException → 429 限流拦截（附带 Retry-After 头）
 * - MethodArgumentNotValidException → 400 参数校验失败（含字段友好的中文提示）
 * - HttpMessageNotReadableException → 400 请求体解析失败
 * - RuntimeException → 500 兜底内部错误
 *
 * 设计要点：
 * - 参数校验错误会将字段名转换为中文（如 username → 用户名），提升用户体验
 * - 限流响应包含 retryAfterSeconds 字段，前端可据此实现自动重试】
 */
@RestControllerAdvice
public class ApiErrors {
    /**
     * 【处理 BadRequestException：返回 400 状态码及错误消息】
     *
     * @param ex 【请求参数异常】
     * @return 【包含 error 字段的 JSON 响应】
     */
    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String, Object>> badRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message(ex)));
    }

    /**
     * 【处理 ModerationBlockedException：返回 422 状态码，
     * 响应体包含错误消息、固定错误码 "MODERATION_BLOCKED" 及被拦截的内容分类列表。】
     *
     * @param ex 【内容审核拦截异常】
     * @return 【包含 error、code、categories 字段的 JSON 响应】
     */
    @ExceptionHandler(ModerationBlockedException.class)
    ResponseEntity<Map<String, Object>> moderation(ModerationBlockedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", message(ex),
                "code", "MODERATION_BLOCKED",
                "categories", ex.getCategories()
        ));
    }

    /**
     * 【处理 UnauthorizedException：返回 401 状态码及错误消息】
     *
     * @param ex 【未授权异常】
     * @return 【包含 error 字段的 JSON 响应】
     */
    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Map<String, Object>> unauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", message(ex)));
    }

    /**
     * 【处理 TooManyRequestsException：返回 429 状态码，
     * 响应头包含 Retry-After（秒），响应体包含错误码、规则名和重试等待时间。】
     *
     * @param ex 【限流拦截异常】
     * @return 【包含 error、code、rule、retryAfterSeconds 字段的 JSON 响应】
     */
    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<Map<String, Object>> rateLimited(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(Map.of(
                        "error", message(ex),
                        "code", "RATE_LIMITED",
                        "rule", ex.getRuleName(),
                        "retryAfterSeconds", ex.getRetryAfterSeconds()));
    }

    /**
     * 【处理 MethodArgumentNotValidException（@Valid 参数校验失败）：
     * 将字段名转换为中文显示，默认错误消息 "must not be blank" 替换为
     * "{中文字段名}不能为空" 的友好提示。】
     *
     * @param ex 【参数校验异常】
     * @return 【包含中文友好错误消息的 400 JSON 响应】
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        var fieldErr = ex.getBindingResult().getFieldError();
        String msg;
        if (fieldErr == null) {
            msg = "请求参数不合法";
        } else {
            var field = fieldErr.getField();
            var raw = fieldErr.getDefaultMessage();
            msg = (raw == null || raw.isBlank() || "must not be blank".equals(raw))
                    ? humanizeField(field) + "不能为空"
                    : raw;
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", msg));
    }

    /**
     * 【处理 HttpMessageNotReadableException（请求体无法解析）：
     * 返回 400 状态码及"请求体不合法"提示。】
     *
     * @param ex 【请求体解析异常】
     * @return 【包含固定中文错误消息的 400 JSON 响应】
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "请求体不合法"));
    }

    /**
     * 【兜底异常处理器：捕获所有未被上述处理器匹配的 RuntimeException，
     * 返回 500 状态码。避免将内部异常栈暴露给前端。】
     *
     * @param ex 【运行时异常】
     * @return 【包含错误消息的 500 JSON 响应】
     */
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> fallback(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", message(ex)));
    }

    /**
     * 【从异常中提取消息文本。若消息为空或空白，回退使用异常类的简单类名。】
     *
     * @param ex 【异常对象】
     * @return 【异常消息文本或类名】
     */
    private static String message(Throwable ex) {
        var m = ex.getMessage();
        return (m == null || m.isBlank()) ? ex.getClass().getSimpleName() : m;
    }

    /**
     * 【将请求参数字段名转换为中文友好的显示名称。
     * 用于参数校验错误提示中，提升前端用户可读性。
     * 支持的字段映射：username→用户名、password→密码、confirmPassword→确认密码、
     * captchaId/captchaCode→验证码、nickname→昵称、email→邮箱。
     * 未映射的字段原样返回。】
     *
     * @param field 【原始字段名】
     * @return 【中文友好的字段显示名称】
     */
    private static String humanizeField(String field) {
        return switch (field) {
            case "username" -> "用户名";
            case "password" -> "密码";
            case "confirmPassword" -> "确认密码";
            case "captchaId", "captchaCode" -> "验证码";
            case "nickname" -> "昵称";
            case "email" -> "邮箱";
            default -> field;
        };
    }
}
