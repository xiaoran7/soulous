package com.soulous.ai.provider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 【LLM Provider 注册中心：从配置（LlmProperties）构建并管理所有 LLM provider 实例。
 *
 * 向后兼容设计：
 * - 当 providers map 为空时，从旧版 soulous.llm.{provider,api-key,model,base-url} 字段
 *   构建单个 provider 并设为默认——兼容旧配置无需迁移。
 * - 多 provider 部署场景下，填充 providers map 并可选指定 default-provider。
 *
 * 使用场景：
 * - Spring 容器中由 LlmService 注入使用；
 * - 外部调用方可通过 get(name) 将特定任务路由到非默认 provider。】
 *
 * <p>Builds and resolves {@link LlmProvider}s from {@link LlmProperties}.</p>
 *
 * <p>Backward-compatible: when {@code providers} is empty we register a single provider built from
 * the legacy {@code soulous.llm.{provider,api-key,model,base-url}} fields, and make it the
 * default. Multi-provider deployments populate the {@code providers} map and (optionally)
 * {@code default-provider}.</p>
 *
 * <p>Used both by Spring ({@link com.soulous.ai.LlmService} injects this) and by callers that want
 * to route a specific task to a non-default provider via {@link #get(String)}.</p>
 */
public class LlmProviderRegistry {
    /** 【provider 注册表，使用 LinkedHashMap 保持插入顺序，便于确定性遍历】 */
    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();

    /** 【默认 provider 的名称，构造时确定后不可变】 */
    private final String defaultName;

    /**
     * 【从配置属性构造注册中心。
     * 如果 providers map 为空（旧版配置），用顶层字段构建单个 provider 并注册为默认。
     * 否则遍历 providers map 逐个构建注册，然后确定默认 provider：
     * 优先使用配置中指定的 default-provider，若未指定或不存在则取第一个。】
     */
    public LlmProviderRegistry(LlmProperties props) {
        if (props.getProviders() == null || props.getProviders().isEmpty()) {
            var legacy = build(
                    nonBlank(props.getProvider(), "mock"),
                    nonBlank(props.getProvider(), "mock"),
                    props.getApiKey(),
                    props.getModel(),
                    props.getBaseUrl(),
                    props.getTimeoutSeconds());
            providers.put(legacy.name(), legacy);
            this.defaultName = legacy.name();
        } else {
            for (var entry : props.getProviders().entrySet()) {
                var p = build(
                        entry.getKey(),
                        entry.getValue().getType(),
                        entry.getValue().getApiKey(),
                        entry.getValue().getModel(),
                        entry.getValue().getBaseUrl(),
                        entry.getValue().getTimeoutSeconds());
                providers.put(p.name(), p);
            }
            var requested = props.getDefaultProvider();
            if (requested != null && !requested.isBlank() && providers.containsKey(requested)) {
                this.defaultName = requested;
            } else {
                this.defaultName = providers.keySet().iterator().next();
            }
        }
    }

    /**
     * 【从单个已构建的 provider 实例构造注册中心——用于测试和旧版构造逻辑。】
     *
     * <p>Construct registry from a single pre-built provider — used by tests and the legacy ctor.</p>
     */
    public LlmProviderRegistry(LlmProvider single) {
        providers.put(single.name(), single);
        this.defaultName = single.name();
    }

    /**
     * 【获取默认的 LLM provider。所有未指定 provider 名称的请求都路由到这里。】
     */
    public LlmProvider getDefault() {
        return providers.get(defaultName);
    }

    /**
     * 【按名称获取 provider。name 为空时返回默认 provider。
     * 返回 Optional 而非直接返回，调用方需处理 provider 不存在的情况。】
     */
    public Optional<LlmProvider> get(String name) {
        if (name == null || name.isBlank()) return Optional.ofNullable(getDefault());
        return Optional.ofNullable(providers.get(name));
    }

    /** 【返回所有已注册的 provider 集合，供管理界面或健康检查使用】 */
    public Collection<LlmProvider> all() {
        return providers.values();
    }

    /** 【返回默认 provider 的名称】 */
    public String defaultName() {
        return defaultName;
    }

    /**
     * 【工厂方法：根据 type 字符串构建对应的 LlmProvider 实例。
     * 支持 "openai"（OpenAI 兼容）、"anthropic"（Anthropic 兼容）、
     * 其他值（含 null）默认构建 MockProvider。
     * type 统一转小写并 trim，确保大小写不敏感。】
     */
    public static LlmProvider build(String name, String type, String apiKey, String model, String baseUrl, int timeoutSeconds) {
        var t = type == null ? "mock" : type.toLowerCase(Locale.ROOT).trim();
        return switch (t) {
            case "openai" -> new OpenAiCompatibleProvider(name, apiKey, model, baseUrl, timeoutSeconds);
            case "anthropic" -> new AnthropicProvider(name, apiKey, model, baseUrl, timeoutSeconds);
            default -> new MockProvider(name);
        };
    }

    /**
     * 【工具方法：返回非空白的字符串，否则返回 fallback。
     * 用于配置字段的默认值回退。】
     */
    private static String nonBlank(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
