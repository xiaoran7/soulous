package com.soulous.ai.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【LLM 配置属性绑定类，将 application.yml 中 soulous.llm.* 前缀的配置映射为 Java 对象】
 *
 * <p>支持两种配置模式：
 * <ul>
 *   <li>旧版单 provider 模式：直接配置 provider/api-key/model/base-url/timeout-seconds</li>
 *   <li>多 provider 模式：通过 providers.<name>.{type,api-key,model,base-url,timeout-seconds} 配置多个 LLM 后端，
 *       并可选地通过 default-provider 指定默认使用的 provider</li>
 * </ul>
 *
 * <p>当 providers 为空时，系统会自动将旧版字段包装为一个以 provider 值为名称的单条目，
 * 从而保证现有部署无需修改配置即可平滑迁移。</p>
 *
 * <p>English: Binds {@code soulous.llm.*}.
 *
 * Two shapes are supported:
 *  - Legacy single-provider: {@code provider/api-key/model/base-url/timeout-seconds}.
 *  - Multi-provider map:     {@code providers.<name>.{type,api-key,model,base-url,timeout-seconds}}
 *                            plus optional {@code default-provider}.
 *
 * If {@code providers} is empty the legacy fields are wrapped into a single entry whose name
 * equals the legacy {@code provider} value — so existing deployments need no config change.</p>
 */
@Component
@ConfigurationProperties(prefix = "soulous.llm")
public class LlmProperties {
    /**
     * 【旧版配置字段：指定 provider 类型标识符，可选值为 "mock"、"anthropic"、"openai" 等。
     * 当 providers 为空时使用此字段确定 LLM 后端。】
     *
     * <p>English: Legacy. "mock" | "anthropic" | "openai". Used when {@link #providers} is empty.</p>
     */
    private String provider = "mock";

    /** 【旧版配置字段：调用 LLM API 所需的认证密钥】 */
    private String apiKey = "";

    /** 【旧版配置字段：使用的模型名称，如 "gpt-4o-mini"、"deepseek-chat" 等】 */
    private String model = "";

    /** 【旧版配置字段：API 基础 URL，用于指定自定义端点（如 Ollama 本地地址、DeepSeek 等第三方兼容端点）】 */
    private String baseUrl = "";

    /**
     * 【请求生命周期总超时时间（秒）。影响所有阻塞式 complete() 调用，包括 AI 审核、拆解、追问、
     * 每日复盘、蒸馏等场景。DeepSeek 在处理长 prompt（RAG 上下文 + 完整历史 + plan 封装）时
     * 通常需要 30-60 秒，旧版默认 30 秒会导致合法请求被截断，因此提升至 90 秒。】
     *
     * <p>【注意：流式传输（postMessageStream）在 OpenAiCompatibleProvider#stream 内部有独立的
     * 更大超时上限，此配置不会截断长推理链。】</p>
     *
     * <p>English: Total request lifecycle timeout in seconds. Affects blocking complete() calls
     * (AI review / decompose / question / daily review / distillation). DeepSeek with
     * long prompts (RAG context + full history + plan envelope) routinely takes 30-60s,
     * so the old 30s default was killing legitimate calls — raised to 90s.
     *
     * <p>Note: streaming (postMessageStream) has its own larger cap inside
     * {@link com.soulous.ai.provider.OpenAiCompatibleProvider#stream} so this knob
     * doesn't cut off long reasoning chains.</p></p>
     */
    private int timeoutSeconds = 90;

    /**
     * 【多 provider 模式下默认使用的 provider 名称。为空时取 providers Map 的第一个键。】
     *
     * <p>English: Which entry of {@link #providers} to use by default. If blank, first key wins.</p>
     */
    private String defaultProvider = "";

    /**
     * 【多 provider 配置映射表，键为 provider 逻辑名称（如 "deepseek"、"openai"），
     * 值为对应的 Spec 配置对象。使用 LinkedHashMap 保持插入顺序，确保默认 provider 选取逻辑稳定。】
     */
    private Map<String, Spec> providers = new LinkedHashMap<>();

    /** 【LLM 响应缓存配置，控制缓存开关、容量上限和过期时间】 */
    private Cache cache = new Cache();

    /**
     * 【单个 LLM Provider 的配置规格，支持 OpenAI 兼容 / Anthropic / Mock 三种类型】
     *
     * <p>English: "openai" | "anthropic" | "mock". OpenAI also covers Ollama / DeepSeek / Moonshot.</p>
     */
    public static class Spec {
        /**
         * 【provider API 类型标识。"openai" 同时兼容 Ollama、DeepSeek、Moonshot 等使用 OpenAI 格式的端点】
         *
         * <p>English: "openai" | "anthropic" | "mock". OpenAI also covers Ollama / DeepSeek / Moonshot.</p>
         */
        private String type = "openai";

        /** 【调用该 provider API 所需的认证密钥。本地端点（如 Ollama）可留空】 */
        private String apiKey = "";

        /** 【该 provider 使用的模型标识符，如 "gpt-4o-mini"、"deepseek-chat" 等】 */
        private String model = "";

        /** 【该 provider 的 API 基础 URL。留空则使用 OpenAI 默认地址；本地 Ollama 通常为 http://localhost:11434/v1】 */
        private String baseUrl = "";

        /**
     * 【请求生命周期总超时时间（秒）。影响所有阻塞式 complete() 调用，包括 AI 审核、拆解、追问、
     * 每日复盘、蒸馏等场景。DeepSeek 在处理长 prompt（RAG 上下文 + 完整历史 + plan 封装）时
     * 通常需要 30-60 秒，旧版默认 30 秒会导致合法请求被截断，因此提升至 90 秒。】
     *
     * <p>【注意：流式传输（postMessageStream）在 OpenAiCompatibleProvider#stream 内部有独立的
     * 更大超时上限，此配置不会截断长推理链。】</p>
     *
     * <p>English: Total request lifecycle timeout in seconds. Affects blocking complete() calls
     * (AI review / decompose / question / daily review / distillation). DeepSeek with
     * long prompts (RAG context + full history + plan envelope) routinely takes 30-60s,
     * so the old 30s default was killing legitimate calls — raised to 90s.
     *
     * <p>Note: streaming (postMessageStream) has its own larger cap inside
     * {@link com.soulous.ai.provider.OpenAiCompatibleProvider#stream} so this knob
     * doesn't cut off long reasoning chains.</p></p>
     */
    private int timeoutSeconds = 90;

        /** 【获取 provider API 类型标识】 */
        public String getType() { return type; }
        /** 【设置 provider API 类型标识】 */
        public void setType(String type) { this.type = type; }
        /** 【获取 API 认证密钥】 */
        public String getApiKey() { return apiKey; }
        /** 【设置 API 认证密钥】 */
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        /** 【获取模型标识符】 */
        public String getModel() { return model; }
        /** 【设置模型标识符】 */
        public void setModel(String model) { this.model = model; }
        /** 【获取 API 基础 URL】 */
        public String getBaseUrl() { return baseUrl; }
        /** 【设置 API 基础 URL】 */
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        /** 【获取超时时间（秒）】 */
        public int getTimeoutSeconds() { return timeoutSeconds; }
        /** 【设置超时时间（秒）】 */
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    /**
     * 【LLM 响应缓存的配置参数。使用 Caffeine 缓存库，支持容量上限和基于写入的 TTL 过期策略。
     * 缓存以 provider + model + systemPrompt + userPrompt 为 key，避免相同请求重复调用 LLM。】
     */
    public static class Cache {
        /** 【是否启用缓存。默认开启，关闭后每次请求都会实际调用 LLM API】 */
        private boolean enabled = true;

        /** 【缓存最大条目数，默认 256。超出后按 LRU 策略淘汰最久未访问的条目】 */
        private int max = 256;

        /** 【缓存条目写入后的存活时间（秒），默认 300 秒（5 分钟）。设为 0 表示永不过期】 */
        private long ttlSeconds = 300;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMax() { return max; }
        public void setMax(int max) { this.max = max; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public Map<String, Spec> getProviders() { return providers; }
    public void setProviders(Map<String, Spec> providers) { this.providers = providers; }
    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }
}
