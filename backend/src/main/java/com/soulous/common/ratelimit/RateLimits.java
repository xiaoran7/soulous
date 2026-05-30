package com.soulous.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 【速率限制容器注解 —— 允许在同一个方法上声明多条 {@link RateLimit} 规则。
 * Java 8+ 的 {@code @Repeatable} 机制要求一个容器注解来持有重复注解的数组。
 * 运行时由 AOP 切面读取，对每条规则逐一检查令牌桶，所有规则均通过才放行。】
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimits {
    /**
     * 【获取该方法上声明的所有速率限制规则数组】
     *
     * @return 【{@link RateLimit} 注解数组】
     */
    RateLimit[] value();
}
