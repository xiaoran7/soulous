package com.soulous.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 【令牌桶注册中心（单进程版）：使用 Caffeine 本地缓存管理 Bucket4j 令牌桶实例。
 *
 * 桶的缓存键格式为 "{ruleName}:{principal}"（规则名:主体标识），
 * 主体标识可以是用户 ID 或客户端 IP。
 *
 * 内存管理策略：
 * - 最大缓存 50,000 个桶实例，超出后按 LRU 淘汰
 * - 桶在 1 小时无访问后自动过期，防止内存泄漏
 *
 * 扩展说明：
 * - 当前为单进程实现，适用于单实例部署
 * - 多实例部署时需切换为 Redis-backed 存储，以实现跨实例共享限流状态】
 *
 * <p>Single-process Bucket4j store. Buckets are keyed by (rule-name, principal) and
 * evicted after 1h of inactivity to bound memory. Switch to a Redis-backed store
 * once the app runs on multiple instances.</p>
 */
@Component
public class BucketRegistry {
    /**
     * 【Caffeine 本地缓存，存储令牌桶实例。
     * 键格式："{ruleName}:{principal}"，值为 Bucket4j Bucket 对象。】
     */
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    /**
     * 【获取或创建指定规则和主体的令牌桶。
     * 若缓存中已存在对应桶实例则直接返回，否则按参数新建桶并放入缓存。
     * 使用贪婪补充策略（Refill.greedy），令牌按固定速率均匀补充。】
     *
     * @param ruleName     【限流规则名称，如 "login"、"api" 等】
     * @param principal    【限流主体标识，如用户 ID 或 IP 地址】
     * @param capacity     【令牌桶容量（最大令牌数）】
     * @param refillTokens 【每个补充周期补充的令牌数】
     * @param refillPeriod 【补充周期的数值】
     * @param refillUnit   【补充周期的时间单位】
     * @return 【对应的 Bucket4j 令牌桶实例】
     */
    public Bucket bucketFor(String ruleName, String principal, long capacity,
                            long refillTokens, long refillPeriod, TimeUnit refillUnit) {
        var key = ruleName + ":" + principal;
        return buckets.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(capacity,
                        Refill.greedy(refillTokens, Duration.ofMillis(refillUnit.toMillis(refillPeriod)))))
                .build());
    }
}
