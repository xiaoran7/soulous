package com.soulous.ai.embedding;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【Embedding 服务门面类（Facade 模式），封装 EmbeddingProvider 的调用逻辑，
 * 并提供进程内的查询缓存和遥测指标。】
 *
 * <p>【设计思路：Embedding 是确定性的——对于相同的 (provider, model, text) 三元组，
 * 总是产生相同的向量。因此 TTL 缓存纯粹是成本/延迟优化，不存在正确性问题。
 * 与 LlmService 不同，这里不需要 namespace 参数，因为向量不是用户特定的：
 * 任何用户输入相同文本都会产生相同的 embedding。】</p>
 *
 * <p>English: Facade over an {@link EmbeddingProvider} with an in-process query cache.
 *
 * Embeddings are deterministic for a given (provider, model, text) tuple, so a TTL cache
 * is purely a cost/latency optimisation — no correctness concerns. We don't add a namespace
 * parameter (unlike {@link com.soulous.ai.LlmService}) because vectors are not user-specific
 * in any sense: the same text from any user produces the same embedding.
 *
 * Telemetry: hits / calls / failures, exposed via {@link #stats()} for admin endpoints.</p>
 */
@Service
public class EmbeddingService {
    /** 【日志记录器】 */
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** 【Embedding provider 实例，负责实际的向量计算】 */
    private final EmbeddingProvider provider;

    /** 【Caffeine 缓存实例，以文本为 key、float 向量为 value，避免重复调用 embedding API】 */
    private final Cache<String, float[]> cache;

    /** 【是否启用缓存】 */
    private final boolean cacheEnabled;

    // ----- 遥测计数器 -----

    /** 【总调用次数】 */
    private final AtomicLong totalCalls = new AtomicLong();

    /** 【缓存命中次数】 */
    private final AtomicLong cacheHits = new AtomicLong();

    /** 【失败次数】 */
    private final AtomicLong failures = new AtomicLong();

    /**
     * 【Spring 自动注入构造函数。从 EmbeddingProperties 配置构建 provider 实例
     * 并初始化 Caffeine 缓存。】
     */
    @Autowired
    public EmbeddingService(EmbeddingProperties props) {
        this.provider = buildProvider(props);
        this.cacheEnabled = props.getCache().isEnabled();
        var max = Math.max(16, props.getCache().getMax());
        var builder = Caffeine.<String, float[]>newBuilder().maximumSize(max);
        var ttl = Math.max(0L, props.getCache().getTtlSeconds());
        if (ttl > 0) builder.expireAfterWrite(Duration.ofSeconds(ttl));
        this.cache = builder.build();
    }

    /**
     * 【测试用构造函数。直接注入任意 EmbeddingProvider 实例，
     * 无需完整的配置和 Spring 容器。】
     *
     * <p>English: Test ctor — inject any provider directly.</p>
     */
    public EmbeddingService(EmbeddingProvider provider, boolean cacheEnabled,
                            int cacheMax, long ttlSeconds) {
        this.provider = provider;
        this.cacheEnabled = cacheEnabled;
        var max = Math.max(16, cacheMax);
        var builder = Caffeine.<String, float[]>newBuilder().maximumSize(max);
        if (ttlSeconds > 0) builder.expireAfterWrite(Duration.ofSeconds(ttlSeconds));
        this.cache = builder.build();
    }

    /** 【检查 embedding provider 是否可用】 */
    public boolean isAvailable() {
        return provider != null && provider.available();
    }

    /** 【返回 embedding 向量的维度（如 768、1536 等）】 */
    public int dimension() {
        return provider == null ? 0 : provider.dimension();
    }

    /** 【返回 provider 名称】 */
    public String providerName() {
        return provider == null ? "" : provider.name();
    }

    /** 【返回使用的模型名称】 */
    public String model() {
        return provider == null ? "" : provider.model();
    }

    /**
     * 【将文本转换为浮点向量。优先从缓存获取，缓存未命中时调用 provider API。
     * 不可用或出错时返回 Optional.empty()，调用方自行决定降级策略。】
     *
     * <p>English: Embed text. Returns empty on unavailable or any error — caller decides how to fall back.</p>
     *
     * @param text 【要向量化的文本】
     * @return 【浮点向量数组，失败时返回 Optional.empty()】
     */
    public Optional<float[]> embed(String text) {
        if (!isAvailable() || text == null || text.isBlank()) return Optional.empty();
        totalCalls.incrementAndGet();
        var key = cacheKey(text);
        if (cacheEnabled) {
            var hit = cache.getIfPresent(key);
            if (hit != null) {
                cacheHits.incrementAndGet();
                return Optional.of(hit);
            }
        }
        try {
            var vec = provider.embed(text);
            if (vec != null && cacheEnabled) cache.put(key, vec);
            return Optional.ofNullable(vec);
        } catch (Exception ex) {
            failures.incrementAndGet();
            log.warn("Embedding failed ({}): {}", provider.name(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** 【清空缓存】 */
    public void clearCache() { cache.invalidateAll(); }

    /** 【获取缓存条目数，先执行 cleanUp() 确保过期条目被淘汰】 */
    public int cacheSize() { cache.cleanUp(); return (int) cache.estimatedSize(); }

    /**
     * 【获取 embedding 服务的运行统计信息，包括 provider 名称、模型、维度、可用状态、
     * 总调用数、缓存命中数、失败数和缓存大小。供管理端点使用。】
     */
    public java.util.Map<String, Object> stats() {
        cache.cleanUp();
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("provider", providerName());
        m.put("model", model());
        m.put("dimension", dimension());
        m.put("available", isAvailable());
        m.put("totalCalls", totalCalls.get());
        m.put("cacheHits", cacheHits.get());
        m.put("failures", failures.get());
        m.put("cacheSize", (int) cache.estimatedSize());
        return m;
    }

    /**
     * 【生成缓存 key。拼接 provider name + model + text，
     * 使用空字符 (\0) 作为分隔符防止歧义。】
     */
    private String cacheKey(String text) {
        return provider.name() + "\0" + provider.model() + "\0" + text;
    }

    /**
     * 【根据配置构建对应的 EmbeddingProvider 实例。采用工厂模式：
     * <ul>
     *   <li>"ollama" / "openai" → OllamaEmbeddingProvider（OpenAI 兼容的 embedding 端点）</li>
     *   <li>"google" / "gemini" → GoogleEmbeddingProvider（Google Generative Language API）</li>
     *   <li>其他 → MockEmbeddingProvider（测试用 mock）</li>
     * </ul>
     * provider 类型不区分大小写，前后空格会被去除。】
     */
    private static EmbeddingProvider buildProvider(EmbeddingProperties p) {
        var type = (p.getProvider() == null ? "mock" : p.getProvider()).toLowerCase(Locale.ROOT).trim();
        return switch (type) {
            case "ollama", "openai" -> new OllamaEmbeddingProvider(
                    type, p.getApiKey(), p.getModel(), p.getBaseUrl(),
                    p.getDimension(), p.getTimeoutSeconds());
            case "google", "gemini" -> new GoogleEmbeddingProvider(
                    type, p.getApiKey(), p.getModel(), p.getBaseUrl(),
                    p.getDimension(), p.getTimeoutSeconds());
            default -> new MockEmbeddingProvider(p.getDimension());
        };
    }
}
