package com.soulous.common.ratelimit;

import com.soulous.auth.UserAccount;
import com.soulous.common.exception.TooManyRequestsException;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 【限流切面：基于 AOP 拦截标注了 {@link RateLimit} 或 {@link RateLimits} 注解的方法，
 * 使用 Bucket4j 令牌桶算法进行速率限制。
 *
 * 工作流程：
 * 1. 检查限流功能是否启用（可通过配置关闭）
 * 2. 从方法注解中提取限流规则参数
 * 3. 根据规则的 KeyType（IP 或 USER）解析限流键
 * 4. 从 BucketRegistry 获取或创建令牌桶
 * 5. 尝试消费令牌，若令牌不足则抛出 TooManyRequestsException
 * 6. 同时通过 Micrometer 记录限流拦截事件，便于监控告警】
 */
@Aspect
@Component
public class RateLimitAspect {
    /** 【令牌桶注册中心，管理所有限流规则对应的 Bucket4j 桶实例】 */
    private final BucketRegistry registry;
    /** 【限流功能开关，通过配置项 soulous.rate-limit.enabled 控制，默认开启】 */
    private final boolean enabled;
    /** 【Micrometer 指标注册中心，用于记录限流拦截事件】 */
    private final MeterRegistry meterRegistry;

    /**
     * 【构造函数：注入依赖并读取限流开关配置】
     *
     * @param registry     【BucketRegistry 令牌桶注册中心】
     * @param meterRegistry 【Micrometer 指标注册中心】
     * @param enabled      【限流功能开关，默认 true】
     */
    public RateLimitAspect(BucketRegistry registry,
                           MeterRegistry meterRegistry,
                           @Value("${soulous.rate-limit.enabled:true}") boolean enabled) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }

    /**
     * 【限流环绕通知：拦截目标方法，执行令牌桶限流逻辑。
     * 支持同一方法上叠加多个 @RateLimit 注解（通过 @RateLimits 容器注解），
     * 所有规则都通过后才放行目标方法。】
     *
     * @param pjp 【连接点，代表被拦截的方法调用】
     * @return 【目标方法的返回值】
     * @throws TooManyRequestsException 【当令牌桶中令牌不足时抛出】
     * @throws Throwable               【目标方法可能抛出的任何异常】
     */
    @Around("@annotation(com.soulous.common.ratelimit.RateLimit) || @annotation(com.soulous.common.ratelimit.RateLimits)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled) return pjp.proceed();
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RateLimit[] limits = method.getAnnotationsByType(RateLimit.class);
        if (limits.length == 0) return pjp.proceed();

        var request = currentRequest();
        for (RateLimit limit : limits) {
            String key = resolveKey(limit.key(), request);
            if (key == null) continue; // unauthenticated USER-scoped: skip (route will 401 anyway)
            var bucket = registry.bucketFor(limit.name(), key,
                    limit.capacity(), limit.refillTokens(), limit.refillPeriod(), limit.refillUnit());
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                long retryAfterSec = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
                var ruleTag = limit.name() == null ? "unknown" : limit.name();
                meterRegistry.counter("soulous.rate_limit.blocked.total", "rule", ruleTag).increment();
                throw new TooManyRequestsException(limit.name(), retryAfterSec,
                        "请求过于频繁，请稍后再试");
            }
        }
        return pjp.proceed();
    }

    /**
     * 【从当前请求上下文中获取 HttpServletRequest 对象。
     * 仅在 Web 请求上下文中可用，非 Web 环境返回 null。】
     *
     * @return 【当前 HTTP 请求对象，非 Web 环境返回 null】
     */
    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest();
        return null;
    }

    /**
     * 【根据限流键类型解析实际的限流键值。
     * - IP 类型：从请求中提取客户端 IP 地址
     * - USER 类型：从安全上下文中提取当前登录用户的 ID】
     *
     * @param type    【限流键类型枚举（IP 或 USER）】
     * @param request 【当前 HTTP 请求对象，可能为 null】
     * @return 【限流键字符串，未认证的 USER 类型返回 null（跳过限流）】
     */
    private static String resolveKey(RateLimit.KeyType type, HttpServletRequest request) {
        return switch (type) {
            case IP -> request == null ? "unknown" : clientIp(request);
            case USER -> currentUserId();
        };
    }

    /**
     * 【从 Spring Security 上下文中获取当前已认证用户的 ID。
     * 仅当认证主体为 UserAccount 类型时才返回 ID，否则返回 null。】
     *
     * @return 【当前用户 ID 的字符串表示，未认证返回 null】
     */
    private static String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserAccount user) {
            return String.valueOf(user.id);
        }
        return null;
    }

    /**
     * 【提取客户端真实 IP 地址。
     * 优先读取 X-Forwarded-For 头的第一跳（适用于反向代理场景），
     * 若不存在则回退到 request.getRemoteAddr()。】
     *
     * <p>honor X-Forwarded-For first hop when present; otherwise remote addr.</p>
     *
     * @param request 【当前 HTTP 请求对象】
     * @return 【客户端 IP 地址字符串】
     */
    private static String clientIp(HttpServletRequest request) {
        // honor X-Forwarded-For first hop when present; otherwise remote addr.
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
