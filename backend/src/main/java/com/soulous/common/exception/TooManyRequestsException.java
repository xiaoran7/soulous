package com.soulous.common.exception;

/**
 * 【请求过多异常 —— 当客户端在单位时间内发送的请求数量超过速率限制（Rate Limit）的桶容量时抛出。
 * 由 AOP 切面 {@code RateLimitAspect} 在令牌桶为空时触发，
 * 全局异常处理器 {@code ApiErrors} 将其转换为 HTTP 429 响应并附带 Retry-After 头，
 * 告知客户端应在多少秒后重试。】
 *
 * <p>Thrown by the rate-limit aspect when a request exceeds its bucket. The handler
 * in ApiErrors translates this to HTTP 429 + Retry-After header.</p>
 */
public class TooManyRequestsException extends RuntimeException {
    /** 【客户端应等待多少秒后才能再次发起请求，写入响应头 Retry-After】 */
    private final long retryAfterSeconds;
    /** 【触发限流的规则名称，如 "auth-login" 或 "ai-hourly"，用于日志追踪和调试】 */
    private final String ruleName;

    /**
     * 【构造请求过多异常】
     *
     * @param ruleName         【触发限流的规则名称】
     * @param retryAfterSeconds 【建议客户端重试前等待的秒数】
     * @param message          【异常的可读描述信息】
     */
    public TooManyRequestsException(String ruleName, long retryAfterSeconds, String message) {
        super(message);
        this.ruleName = ruleName;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 【获取建议的重试等待秒数】
     *
     * @return 【客户端应等待的秒数】
     */
    public long getRetryAfterSeconds() { return retryAfterSeconds; }

    /**
     * 【获取触发限流的规则名称】
     *
     * @return 【限流规则名称】
     */
    public String getRuleName() { return ruleName; }
}
