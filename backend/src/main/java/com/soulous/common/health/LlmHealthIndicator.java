package com.soulous.common.health;

import com.soulous.ai.LlmService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 【LLM 健康检查指示器：向配置的默认 LLM 供应商发送一次最小化补全请求，
 * 以验证 LLM 服务是否端到端可用。
 *
 * 默认关闭，需设置 {@code soulous.health.llm-check.enabled=true} 启用。
 *
 * 特殊处理：
 * - 当供应商为 "mock" 时返回 UNKNOWN 状态，避免开发/测试环境消耗 API 额度
 * - 当 LlmService 报告不可用时直接返回 UNKNOWN，跳过探测】
 *
 * <p>Fires a minimal completion against the configured default LLM provider.
 * Off by default; flip {@code soulous.health.llm-check.enabled=true} to opt in.</p>
 *
 * <p>Returns {@code UNKNOWN} when the provider is "mock" so dev/test setups don't burn
 * API credits, and short-circuits to {@code UNKNOWN} when LlmService reports unavailable.</p>
 */
@Component
@ConditionalOnProperty(name = "soulous.health.llm-check.enabled", havingValue = "true", matchIfMissing = false)
public class LlmHealthIndicator implements HealthIndicator {
    /** 【探测请求超时时间（秒）】 */
    private static final long TIMEOUT_SECONDS = 5;

    /** 【LLM 服务实例，负责与底层 LLM 供应商通信】 */
    private final LlmService llm;

    /**
     * 【构造函数：注入 LLM 服务实例】
     *
     * @param llm 【LlmService 实例】
     */
    public LlmHealthIndicator(LlmService llm) {
        this.llm = llm;
    }

    /**
     * 【执行健康检查逻辑：
     * 1. 获取 LLM 供应商信息
     * 2. 若供应商为 mock，直接返回 UNKNOWN（跳过探测）
     * 3. 若 LLM 服务不可用，直接返回 UNKNOWN
     * 4. 异步发送最小化补全请求（带 5 秒超时）
     * 5. 根据响应结果返回 UP/DOWN 状态】
     *
     * @return 【Health 状态对象，包含供应商、模型等详细信息】
     */
    @Override
    public Health health() {
        var info = llm.info();
        var provider = info.getOrDefault("provider", "");
        if ("mock".equalsIgnoreCase(provider)) {
            return Health.unknown().withDetail("provider", provider)
                    .withDetail("reason", "mock provider — probe skipped").build();
        }
        if (!llm.isAvailable()) {
            return Health.unknown().withDetail("provider", provider)
                    .withDetail("reason", "provider not available").build();
        }
        try {
            var future = CompletableFuture.supplyAsync(() ->
                    llm.complete("You are a probe. Reply with a single token.", "ping"));
            var result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result.isPresent()) {
                return Health.up().withDetail("provider", provider)
                        .withDetail("model", info.getOrDefault("model", "")).build();
            }
            return Health.down().withDetail("provider", provider)
                    .withDetail("reason", "empty response").build();
        } catch (TimeoutException ex) {
            return Health.down().withDetail("provider", provider)
                    .withDetail("reason", "timeout after " + TIMEOUT_SECONDS + "s").build();
        } catch (Exception ex) {
            return Health.down().withException(ex).build();
        }
    }
}
