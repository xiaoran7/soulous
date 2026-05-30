package com.soulous;

import com.soulous.common.exception.TooManyRequestsException;
import com.soulous.common.ratelimit.RateLimit;
import com.soulous.common.ratelimit.RateLimitAspect;
import com.soulous.common.ratelimit.BucketRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【RateLimitAspect 限流切面的单元测试，基于令牌桶算法。
 *  覆盖场景：
 *  1. 允许请求直到容量耗尽，之后抛出 TooManyRequestsException
 *  2. 不同 IP 拥有独立的令牌桶（互不影响）
 *  3. 限流开关关闭时限流逻辑短路（所有请求放行）
 *  使用 AspectJProxyFactory 手动织入切面，MockHttpServletRequest 模拟 IP。】
 *
 * <p>Unit tests for the RateLimitAspect token-bucket rate limiting.</p>
 */
class RateLimitTests {

    /**
     * 【被限流注解标记的测试端点方法：
     *  桶容量 3，每分钟补充 3 个令牌，按 IP 限流】
     */
    static class IpEndpoint {
        @RateLimit(name = "test-ip-burst", capacity = 3, refillTokens = 3, refillPeriod = 1,
                refillUnit = TimeUnit.MINUTES, key = RateLimit.KeyType.IP)
        public String hit() {
            return "ok";
        }
    }

    /**
     * 【辅助方法：使用 AspectJProxyFactory 为端点织入限流切面】
     * @param target 被代理的目标对象
     * @param enabled 限流是否启用
     */
    private static IpEndpoint wrap(IpEndpoint target, boolean enabled) {
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(new RateLimitAspect(new BucketRegistry(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), enabled));
        return factory.getProxy();
    }

    /**
     * 【辅助方法：绑定 MockHttpServletRequest 设置远程 IP 地址】
     */
    private static void bindRequest(String ip) {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    /**
     * 【测试令牌桶限流：允许 3 次请求（桶容量），第 4 次抛出 TooManyRequestsException。
     *  验证异常包含规则名称 "test-ip-burst" 和正的 retryAfterSeconds。】
     */
    @Test
    void allowsUpToCapacityThenBlocks() {
        var ep = wrap(new IpEndpoint(), true);
        bindRequest("10.0.0.1");

        assertThat(ep.hit()).isEqualTo("ok");
        assertThat(ep.hit()).isEqualTo("ok");
        assertThat(ep.hit()).isEqualTo("ok");
        assertThatThrownBy(ep::hit)
                .isInstanceOf(TooManyRequestsException.class)
                .satisfies(ex -> {
                    var tmre = (TooManyRequestsException) ex;
                    assertThat(tmre.getRuleName()).isEqualTo("test-ip-burst");
                    assertThat(tmre.getRetryAfterSeconds()).isGreaterThan(0);
                });
    }

    /**
     * 【测试不同 IP 拥有独立的令牌桶。
     *  IP 10.0.0.2 用尽 3 次后被限流，
     *  IP 10.0.0.3 仍有完整 3 次配额不受影响。】
     */
    @Test
    void differentIpsHaveIndependentBuckets() {
        var ep = wrap(new IpEndpoint(), true);

        bindRequest("10.0.0.2");
        ep.hit(); ep.hit(); ep.hit();
        assertThatThrownBy(ep::hit).isInstanceOf(TooManyRequestsException.class);

        bindRequest("10.0.0.3");
        // fresh bucket — three should all succeed
        // 【新桶 — 3 次应全部成功】
        assertThat(ep.hit()).isEqualTo("ok");
        assertThat(ep.hit()).isEqualTo("ok");
        assertThat(ep.hit()).isEqualTo("ok");
    }

    /**
     * 【测试限流开关关闭时限流逻辑短路。
     *  enabled=false 时，连续 50 次请求全部放行（正常情况第 4 次应被限流）。】
     */
    @Test
    void disabledFlagShortCircuits() {
        var ep = wrap(new IpEndpoint(), false);
        bindRequest("10.0.0.4");
        // would normally fail on the 4th hit, but flag is off
        // 【正常情况第 4 次应失败，但开关关闭】
        for (int i = 0; i < 50; i++) {
            assertThat(ep.hit()).isEqualTo("ok");
        }
    }
}
