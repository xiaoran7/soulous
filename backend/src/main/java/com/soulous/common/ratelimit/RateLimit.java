package com.soulous.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 【应用层速率限制注解 —— 通过 AOP 切面实现基于令牌桶算法的请求限流。
 * 切面会根据 {@link #key()} 指定的维度（IP 地址或已认证用户 ID）解析限流键，
 * 查找由 {@link #name()} 命名的 Bucket4j 令牌桶；当桶内无可用令牌时抛出
 * {@link com.soulous.common.exception.TooManyRequestsException}。
 *
 * <p>同一方法上的多个 @RateLimit 采用 AND 逻辑组合：所有规则都必须有可用令牌请求才能继续。
 * 适用于 "每小时 60 次 且 每天 200 次" 这类多维度限流场景。</p>】
 *
 * <p>Application-layer rate limit. Applied via AOP — the aspect resolves the key
 * (IP or authenticated user id), looks up the named Bucket4j bucket, and throws
 * TooManyRequestsException when the bucket is empty.</p>
 *
 * <p>Multiple @RateLimit on the same method are AND-combined: all must have a token
 * available for the call to proceed. Useful for e.g. "60/hour AND 200/day".</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@java.lang.annotation.Repeatable(RateLimits.class)
public @interface RateLimit {
    /**
     * 【限流规则的唯一名称，如 "auth-login" 或 "ai-hourly"。
     * 令牌桶按 name + key 的组合进行隔离，不同规则互不干扰。】
     *
     * <p>Unique name for this rule, e.g. "auth-login" or "ai-hourly". Buckets are scoped by name + key.</p>
     */
    String name();

    /**
     * 【令牌桶的最大容量（突发大小），即同一时刻最多可消费的令牌数。】
     *
     * <p>Maximum tokens in the bucket (burst size).</p>
     */
    long capacity();

    /**
     * 【每个补给周期补充的令牌数。常见模式：capacity 个令牌在 period 内均匀补充（平滑限流）。】
     *
     * <p>Number of tokens refilled per refillPeriod. Typical pattern: capacity tokens per period (smooth).</p>
     */
    long refillTokens();

    /**
     * 【令牌补给的时间周期长度，配合 {@link #refillUnit()} 使用。】
     */
    long refillPeriod();

    /**
     * 【令牌补给周期的时间单位，默认为分钟。】
     */
    TimeUnit refillUnit() default TimeUnit.MINUTES;

    /**
     * 【限流键的类型 —— 决定按客户端 IP 还是已认证用户 ID 进行限流。】
     *
     * <p>Whether the key is the client IP or the authenticated user id.</p>
     */
    KeyType key() default KeyType.IP;

    /**
     * 【限流键类型枚举：
     * IP   —— 按客户端 IP 地址限流（适用于未登录接口）；
     * USER —— 按已认证用户 ID 限流（适用于已登录接口）。】
     */
    enum KeyType { IP, USER }
}
