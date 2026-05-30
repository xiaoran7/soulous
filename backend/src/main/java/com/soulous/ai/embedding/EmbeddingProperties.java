package com.soulous.ai.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 【Embedding 模型的配置属性类，绑定 application.yml 中的 soulous.embedding.* 前缀。
 * 设计为单 provider 模式（比 LLM 侧简单，因为检索几乎总是使用同一个 embedding 模型——
 * 混用不同维度的向量是 bug 而非特性）。如果将来需要多 provider，可参考 LlmProperties 实现。】
 *
 * <p>Binds {@code soulous.embedding.*}.</p>
 *
 * <p>Single-provider for now (simpler than the LLM side because retrieval almost always uses
 * one consistent embedding model — mixing dimensions across stored vectors is a bug, not a
 * feature). If we ever need multi-provider here, mirror {@code LlmProperties}.</p>
 */
@Component
@ConfigurationProperties(prefix = "soulous.embedding")
public class EmbeddingProperties {
    /** 【embedding 提供者类型，可选值："ollama" | "openai" | "mock"。默认 mock 以便单元测试离线运行】 */
    private String provider = "mock";

    /** 【API 密钥，用于需要认证的 embedding 服务（如 OpenAI）。本地 Ollama 可留空】 */
    private String apiKey = "";

    /** 【使用的 embedding 模型名称，如 "nomic-embed-text"、"text-embedding-3-small" 等】 */
    private String model = "";

    /** 【embedding 服务的 base URL，如 "http://localhost:11434/v1"。留空则使用各 provider 的默认地址】 */
    private String baseUrl = "";

    /** 【向量维度，必须与模型实际输出维度一致。错误的值会导致存储的向量维度不匹配，引发检索异常】 */
    private int dimension = 768;

    /** 【HTTP 请求超时时间（秒），防止 embedding 服务无响应时长时间阻塞】 */
    private int timeoutSeconds = 30;

    private Cache cache = new Cache();

    /**
     * 【Embedding 结果的缓存配置。因为相同文本的 embedding 结果是确定性的，
     * 缓存可以显著减少重复调用的开销。默认启用，最多缓存 512 条，TTL 为 1 小时。】
     */
    public static class Cache {
        /** 【是否启用缓存，默认 true】 */
        private boolean enabled = true;

        /** 【缓存最大条目数，默认 512。超出后按 LRU 策略淘汰】 */
        private int max = 512;

        /** 【缓存过期时间（秒），默认 3600（1 小时）。embedding 结果是确定性的，1 小时已经很保守】 */
        private long ttlSeconds = 3600; // embeddings are deterministic; 1h is conservative

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
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }
}
