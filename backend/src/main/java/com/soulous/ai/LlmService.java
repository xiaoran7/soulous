package com.soulous.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.soulous.ai.provider.LlmProperties;
import com.soulous.ai.provider.LlmProvider;
import com.soulous.ai.provider.LlmProviderRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【LLM 服务门面类（Facade 模式），统一封装一个或多个 LLM Provider 的调用逻辑，
 * 为上层业务（AI 审核、拆解、追问、每日复盘、蒸馏等）提供简洁的调用接口。】
 *
 * <p>【核心能力：
 * <ul>
 *   <li>多 provider 路由：调用方可指定 provider 名称锁定特定后端（如蒸馏用便宜模型、审核用强模型），
 *       也可使用默认 provider</li>
 *   <li>有界 LRU + TTL 响应缓存：以 provider + model + system + user 为 key，
 *       避免相同请求重复调用 LLM，节省成本和延迟</li>
 *   <li>命名空间隔离：namespace 参数（通常为用户 ID）分区缓存，防止不同用户共享缓存响应</li>
 *   <li>遥测指标：记录总调用数、缓存命中数、成功/失败计数和最后一次失败信息</li>
 *   <li>优雅降级：任何错误返回 Optional.empty()，调用方可回退到基于规则的逻辑</li>
 * </ul>
 * </p>
 *
 * <p>English: Facade over one or more {@link LlmProvider}s. Adds:
 *   - bounded LRU + TTL response cache (keyed by provider + model + system + user)
 *   - telemetry: total / hit / success / failure counters + last failure record
 *   - graceful fallback: returns {@code Optional.empty()} on any error so callers can use
 *     rule-based logic instead of crashing
 *
 * Routing: callers can pass a provider name to {@link #complete(String, String, String)} to
 * pin a request to a specific provider (e.g. cheap model for distillation, strong model for
 * moderation). Most callers use the {@link #complete(String, String)} overload which uses
 * the default provider configured in {@code soulous.llm.default-provider}.</p>
 */
@Service
public class LlmService {
    /** 【日志记录器，用于记录 LLM 调用的警告和错误信息】 */
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    /** 【provider 注册表，管理所有已注册的 LLM provider 实例及其路由逻辑】 */
    private final LlmProviderRegistry registry;

    /** 【JSON 序列化器，用于解析 LLM 返回的 JSON 响应】 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 【是否启用响应缓存】 */
    private final boolean cacheEnabled;

    /** 【Caffeine 缓存实例，存储 LLM 响应文本，支持 LRU 淘汰和 TTL 过期】 */
    private final Cache<String, String> cache;

    /** 【Micrometer 指标注册器，用于记录延迟计时器和调用计数器】 */
    private final MeterRegistry meterRegistry;

    // ----- 遥测计数器 -----

    /** 【总调用次数，包含缓存命中和实际 API 调用】 */
    private final AtomicLong totalCalls = new AtomicLong();

    /** 【缓存命中次数】 */
    private final AtomicLong cacheHits = new AtomicLong();

    /** 【成功调用次数】 */
    private final AtomicLong successes = new AtomicLong();

    /** 【失败调用次数】 */
    private final AtomicLong failures = new AtomicLong();

    /** 【最近一次失败记录，包含 provider 名称、截断的错误消息和时间戳。volatile 保证多线程可见性】 */
    private volatile FailureRecord lastFailure;

    /**
     * 【Spring 自动注入构造函数。从 LlmProperties 配置构建（可能是多 provider 的）注册表，
     * 并根据缓存配置初始化 Caffeine 缓存实例。】
     *
     * <p>English: Spring ctor — uses {@link LlmProperties} to build a (possibly multi-provider) registry.</p>
     */
    @Autowired
    public LlmService(LlmProperties props, MeterRegistry meterRegistry) {
        this(new LlmProviderRegistry(props), props.getCache().isEnabled(),
                props.getCache().getMax(), props.getCache().getTtlSeconds(), meterRegistry);
    }

    /**
     * 【旧版构造函数，供测试使用。接受扁平参数构建单 provider 注册表，
     * 使 LlmService 无需 Spring 容器即可直接实例化。】
     *
     * <p>English: Legacy ctor used by tests. Builds a single-provider registry from the same flat args
     * the original implementation took, so {@link com.soulous.ai.LlmService} can still be
     * constructed directly without Spring.</p>
     */
    public LlmService(String provider, String apiKey, String model, String baseUrl,
                      int timeoutSeconds, boolean cacheEnabled, int cacheMax, long cacheTtlSeconds) {
        this(buildLegacyRegistry(provider, apiKey, model, baseUrl, timeoutSeconds),
                cacheEnabled, cacheMax, cacheTtlSeconds, new SimpleMeterRegistry());
    }

    /**
     * 【内部构造函数，统一处理 Spring 注入和测试构造两种场景。
     * 初始化 Caffeine 缓存，配置 Ticker 使用可覆写的 now() 方法，
     * 以便 TTL 测试可以通过模拟时钟推进时间而不依赖真实系统时间。】
     */
    private LlmService(LlmProviderRegistry registry, boolean cacheEnabled, int cacheMax, long cacheTtlSeconds,
                       MeterRegistry meterRegistry) {
        this.registry = registry;
        this.cacheEnabled = cacheEnabled;
        this.meterRegistry = meterRegistry;
        var max = Math.max(16, cacheMax);
        // 【Caffeine Ticker 通过可覆写的 now() 方法读取时间，使 TTL 测试能使用模拟时钟（推进假时间）
        // 而不影响真实系统时间，保证测试的确定性。】
        // English: Caffeine ticker reads through our overridable now() so the existing TTL test
        // (which advances a fake clock) keeps working without touching wall time.
        Ticker ticker = () -> now() * 1_000_000L;
        var builder = Caffeine.newBuilder().ticker(ticker).maximumSize(max);
        var ttl = Math.max(0L, cacheTtlSeconds);
        if (ttl > 0) builder.expireAfterWrite(Duration.ofSeconds(ttl));
        this.cache = builder.build();
    }

    /**
     * 【从旧版扁平参数构建单 provider 注册表的工厂方法。
     * 将 provider 名称规范化为小写后调用 LlmProviderRegistry.build() 创建单条目注册表。】
     */
    private static LlmProviderRegistry buildLegacyRegistry(String provider, String apiKey, String model,
                                                            String baseUrl, int timeoutSeconds) {
        var name = provider == null || provider.isBlank() ? "mock" : provider.toLowerCase().trim();
        return new LlmProviderRegistry(LlmProviderRegistry.build(name, name, apiKey, model, baseUrl, timeoutSeconds));
    }

    // ----- 公共 API -----

    /**
     * 【检查默认 provider 是否可用。用于上层业务决定是否回退到规则引擎。】
     */
    public boolean isAvailable() {
        var p = registry.getDefault();
        return p != null && p.available();
    }

    /**
     * 【使用默认 provider 进行文本完成。失败或不可用时返回 Optional.empty()，
     * 调用方可据此回退到基于规则的逻辑。】
     *
     * <p>English: Default-provider completion. Returns empty on unavailable or any error.</p>
     */
    public Optional<String> complete(String systemPrompt, String userPrompt) {
        return complete(null, systemPrompt, userPrompt);
    }

    /**
     * 【与 complete() 相同，但将响应解析为 JSON。在 prompt 末尾自动追加"请只用 JSON 回答"的指令。】
     *
     * <p>English: Same as {@link #complete(String, String)} but parses the response as JSON.</p>
     */
    public Optional<JsonNode> completeJson(String systemPrompt, String userPrompt) {
        return completeJson(null, systemPrompt, userPrompt);
    }

    /**
     * 【指定 provider 名称的文本完成。用于需要锁定特定 LLM 后端的场景
     * （如蒸馏用便宜模型、审核用强模型）。】
     *
     * <p>English: Pin a request to a specific provider (must exist in the registry).</p>
     */
    public Optional<String> complete(String providerName, String systemPrompt, String userPrompt) {
        return complete(null, providerName, systemPrompt, userPrompt);
    }

    /**
     * 【带命名空间的文本完成方法。namespace 参数用于缓存分区——通常传入用户 ID，
     * 使得两个用户即使问同样的通用问题也不会共享缓存响应。
     * 传 null 或空字符串则使用跨用户的共享缓存。
     *
     * <p>执行流程：
     * 1. 从注册表获取指定 provider，不可用时直接返回 empty
     * 2. 检查缓存（如启用），命中则直接返回并记录缓存命中
     * 3. 调用 invoke() 实际请求 LLM API
     * 4. 成功时将结果写入缓存，记录成功指标
     * 5. 失败时记录失败信息和日志，返回 empty（优雅降级）
     * 6. finally 块中记录延迟计时器和调用计数器（无论成功/失败）</p>
     *
     * @param namespace    【缓存命名空间，通常为用户 ID，用于缓存分区隔离。null 或空表示共享缓存】
     * @param providerName 【provider 名称，为 null 时使用默认 provider】
     * @param systemPrompt 【系统提示词】
     * @param userPrompt   【用户提示词】
     * @return 【LLM 生成的文本，失败或不可用时返回 Optional.empty()】
     */
    public Optional<String> complete(String namespace, String providerName, String systemPrompt, String userPrompt) {
        return complete(namespace, providerName, systemPrompt, userPrompt, false);
    }

    /**
     * 【带命名空间和 JSON 模式的核心完成方法。jsonMode 为 true 时走 {@link #invokeJson} 分发，
     * 请求底层 provider 返回严格 JSON；为 false 时走 {@link #invoke} 文本分发。
     * 缓存 key 含 jsonMode 维度，避免同一 prompt 的文本/JSON 两种响应互相串味。
     * 其余逻辑（缓存、遥测、优雅降级）与文本路径一致。】
     *
     * @param namespace    【缓存命名空间，通常为用户 ID】
     * @param providerName 【provider 名称，为 null 时使用默认 provider】
     * @param systemPrompt 【系统提示词】
     * @param userPrompt   【用户提示词】
     * @param jsonMode     【为 true 时请求严格 JSON 输出】
     * @return 【LLM 生成的文本，失败或不可用时返回 Optional.empty()】
     */
    public Optional<String> complete(String namespace, String providerName, String systemPrompt, String userPrompt,
                                     boolean jsonMode) {
        var provider = registry.get(providerName).orElse(null);
        if (provider == null || !provider.available()) return Optional.empty();
        totalCalls.incrementAndGet();
        var key = cacheKey(namespace, provider, systemPrompt, userPrompt, jsonMode);
        if (cacheEnabled) {
            var hit = cache.getIfPresent(key);
            if (hit != null) {
                cacheHits.incrementAndGet();
                return Optional.of(hit);
            }
        }
        var providerTag = provider.name() == null ? "unknown" : provider.name();
        var modelTag = provider.model() == null ? "unknown" : provider.model();
        var sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            var result = jsonMode
                    ? invokeJson(provider.name(), systemPrompt, userPrompt)
                    : invoke(provider.name(), systemPrompt, userPrompt);
            successes.incrementAndGet();
            if (cacheEnabled && result != null) cache.put(key, result);
            outcome = "success";
            return Optional.ofNullable(result);
        } catch (Exception ex) {
            failures.incrementAndGet();
            lastFailure = new FailureRecord(provider.name(), truncate(ex.getMessage()), now());
            log.warn("LLM call failed ({}): {}", provider.name(), ex.getMessage());
            return Optional.empty();
        } finally {
            sample.stop(meterRegistry.timer("soulous.llm.latency", "provider", providerTag, "model", modelTag));
            meterRegistry.counter("soulous.llm.calls.total",
                    "provider", providerTag, "model", modelTag, "outcome", outcome).increment();
        }
    }

    /**
     * 【指定 provider 的 JSON 完成，委托给 completeJson(namespace, ...)】
     *
     * <p>English: Same as complete but parses response as JSON.</p>
     */
    public Optional<JsonNode> completeJson(String providerName, String systemPrompt, String userPrompt) {
        return completeJson(null, providerName, systemPrompt, userPrompt);
    }

    /**
     * 【带命名空间的 JSON 完成。在 user prompt 末尾追加"请只用 JSON 回答"指令，
     * 然后尝试从响应中提取 JSON 对象。】
     *
     * @param namespace    【缓存命名空间】
     * @param providerName 【provider 名称】
     * @param systemPrompt 【系统提示词】
     * @param userPrompt   【用户提示词】
     * @return 【解析后的 JSON 节点，解析失败时返回 Optional.empty()】
     */
    public Optional<JsonNode> completeJson(String namespace, String providerName, String systemPrompt, String userPrompt) {
        return complete(namespace, providerName, systemPrompt,
                userPrompt + "\n\n请只用 JSON 回答，不要任何解释、Markdown 围栏或多余文字。", true)
                .flatMap(this::extractJson);
    }

    /**
     * 【带一次自纠重试的 JSON 完成。先正常 {@link #completeJson} 一次；若解析失败或结果未通过
     * {@code valid} 校验，则把失败原因回灌给模型再试一次（"上一次输出不符合要求，请严格只输出合法 JSON"）。
     * 仍失败时返回 {@code Optional.empty()}，调用方据此回退到规则兜底。
     *
     * <p>这是 harness 层的可靠性手段：单次调用偶发的 JSON 不合法 / 缺字段，多数能靠一次带错误上下文的
     * 重试纠正，避免直接退化到质量更低的规则模板。重试与放弃都打点（{@code soulous.llm.json.retry} /
     * {@code soulous.llm.json.give_up}），便于观测拆解链路的真实健康度。</p>
     *
     * @param namespace    【缓存命名空间，通常为用户 ID】
     * @param providerName 【provider 名称，为 null 时使用默认 provider】
     * @param systemPrompt 【系统提示词】
     * @param userPrompt   【用户提示词】
     * @param valid        【对解析出的 JSON 做业务校验的谓词；返回 false 视为不合格、触发重试】
     * @return 【通过校验的 JSON 节点；两次都失败时返回 Optional.empty()】
     */
    public Optional<JsonNode> completeJsonValidated(String namespace, String providerName,
                                                    String systemPrompt, String userPrompt,
                                                    java.util.function.Predicate<JsonNode> valid) {
        return validateWithRetry(u -> completeJson(namespace, providerName, systemPrompt, u),
                userPrompt, providerName, valid);
    }

    /**
     * 【便捷重载：默认 provider 的带自纠重试 JSON 完成。内部走可被测试子类覆写的
     * {@link #completeJson(String, String)}，使现有桩实现无需改动即可参与自纠路径。】
     */
    public Optional<JsonNode> completeJsonValidated(String systemPrompt, String userPrompt,
                                                    java.util.function.Predicate<JsonNode> valid) {
        return validateWithRetry(u -> completeJson(systemPrompt, u), userPrompt, "default", valid);
    }

    /**
     * 【自纠重试核心：先调用一次 {@code call}，结果缺失或未通过 {@code valid} 时，带失败原因
     * 回灌再调一次；仍失败返回 empty。重试 / 放弃分别打点。】
     *
     * @param call         【给定 user prompt 返回 JSON 的调用（绑定了 system/namespace/provider）】
     * @param userPrompt   【原始 user prompt】
     * @param providerTag  【遥测标签用的 provider 名】
     * @param valid        【业务校验谓词，null 表示只要能解析即可】
     */
    private Optional<JsonNode> validateWithRetry(java.util.function.Function<String, Optional<JsonNode>> call,
                                                 String userPrompt, String providerTag,
                                                 java.util.function.Predicate<JsonNode> valid) {
        var first = call.apply(userPrompt);
        if (first.isPresent() && (valid == null || valid.test(first.get()))) return first;

        meterRegistry.counter("soulous.llm.json.retry",
                "provider", providerTag == null ? "default" : providerTag).increment();
        var reason = first.isEmpty() ? "输出无法解析为 JSON" : "输出是合法 JSON 但缺少必要字段或结构不符";
        var corrective = userPrompt + "\n\n（上一次回答" + reason + "。请严格只输出符合要求的合法 JSON，不要任何解释或 Markdown 围栏。）";
        var second = call.apply(corrective);
        if (second.isPresent() && (valid == null || valid.test(second.get()))) return second;

        meterRegistry.counter("soulous.llm.json.give_up",
                "provider", providerTag == null ? "default" : providerTag).increment();
        return Optional.empty();
    }

    /**
     * 【分发钩子方法。按名称从注册表解析 provider 并调用其 complete()。
     * 测试子类可覆写此方法绕过 HTTP 调用。设为 public 以保持
     * 现有 TestableLlm 子类的兼容性。】
     *
     * <p>English: Dispatch hook. Resolves provider by name and calls it. Tests override this to bypass HTTP.
     * Visible so the existing {@code TestableLlm} subclass keeps working.</p>
     *
     * @param providerName 【provider 名称】
     * @param systemPrompt 【系统提示词】
     * @param userPrompt   【用户提示词】
     * @return 【LLM 生成的文本】
     * @throws Exception 【provider 未找到或 API 调用失败时抛出】
     */
    public String invoke(String providerName, String systemPrompt, String userPrompt) throws Exception {
        var provider = registry.get(providerName)
                .orElseThrow(() -> new IllegalStateException("unknown provider " + providerName));
        return provider.complete(systemPrompt, userPrompt);
    }

    /**
     * 【JSON 模式分发钩子。解析 provider 并以 jsonMode=true 调用其 complete()，请求严格 JSON 输出。
     * 与 {@link #invoke} 分开，使文本路径的测试覆写（覆写 invoke）不受影响——JSON 路径的测试可单独
     * 覆写本方法。】
     *
     * <p>English: JSON-mode dispatch hook. Kept separate from {@link #invoke} so text-path test overrides
     * stay intact; JSON-path tests override this one.</p>
     *
     * @param providerName 【provider 名称】
     * @param systemPrompt 【系统提示词】
     * @param userPrompt   【用户提示词】
     * @return 【LLM 生成的（期望为 JSON 的）文本】
     * @throws Exception 【provider 未找到或 API 调用失败时抛出】
     */
    public String invokeJson(String providerName, String systemPrompt, String userPrompt) throws Exception {
        var provider = registry.get(providerName)
                .orElseThrow(() -> new IllegalStateException("unknown provider " + providerName));
        return provider.complete(systemPrompt, userPrompt, true);
    }

    /**
     * 【使用默认 provider 的流式完成。故意绕过缓存——流式调用几乎总是用于对话场景，
     * 每次输入都是唯一的。遥测仍记录调用（计数器 + 延迟计时器），失败时抛出异常
     * 以便控制器通过 SSE 向客户端发送错误事件。】
     *
     * @param systemPrompt 【系统提示词】
     * @param userPrompt   【用户提示词】
     * @param onChunk      【每收到一个增量文本片段时的回调函数】
     * @return 【provider 流式传输的完整累积文本；provider 不可用时返回空字符串】
     */
    public String stream(String systemPrompt, String userPrompt, java.util.function.Consumer<String> onChunk) {
        var provider = registry.getDefault();
        if (provider == null || !provider.available()) return "";
        totalCalls.incrementAndGet();
        var providerTag = provider.name() == null ? "unknown" : provider.name();
        var modelTag = provider.model() == null ? "unknown" : provider.model();
        var sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            var full = provider.stream(systemPrompt, userPrompt, onChunk);
            successes.incrementAndGet();
            outcome = "success";
            return full == null ? "" : full;
        } catch (Exception ex) {
            failures.incrementAndGet();
            lastFailure = new FailureRecord(provider.name(), truncate(ex.getMessage()), now());
            log.warn("LLM stream failed ({}): {}", provider.name(), ex.getMessage());
            throw new RuntimeException("LLM stream failed: " + ex.getMessage(), ex);
        } finally {
            sample.stop(meterRegistry.timer("soulous.llm.latency", "provider", providerTag, "model", modelTag, "mode", "stream"));
            meterRegistry.counter("soulous.llm.calls.total",
                    "provider", providerTag, "model", modelTag, "outcome", outcome, "mode", "stream").increment();
        }
    }

    /**
     * 【默认 provider 是否支持真正的增量流式传输。用于能力门控——
     * 如果不支持，前端不应使用 SSE 而应使用长轮询。】
     *
     * <p>English: True when the default provider supports real (incremental) streaming.</p>
     */
    public boolean supportsStreaming() {
        var p = registry.getDefault();
        return p != null && p.available() && p.supportsStreaming();
    }

    /**
     * 【可覆写的时钟方法，用于缓存 TTL 测试。默认返回系统当前毫秒时间戳，
     * 测试中可覆写为模拟时钟以控制时间推进。】
     *
     * <p>English: Overridable clock for cache-TTL tests.</p>
     */
    public long now() {
        return System.currentTimeMillis();
    }

    // ----- 缓存管理 -----

    /**
     * 【生成缓存 key。拼接 namespace + provider name + model + system prompt + user prompt，
     * 使用空字符 (\0) 作为分隔符防止不同字段拼接后产生歧义。】
     */
    private String cacheKey(String namespace, LlmProvider p, String system, String user, boolean jsonMode) {
        return (namespace == null ? "" : namespace) + "\0"
                + p.name() + "\0" + p.model() + "\0"
                + (jsonMode ? "json" : "text") + "\0"
                + (system == null ? "" : system) + "\0"
                + (user == null ? "" : user);
    }

    /** 【清空所有缓存条目。通常在配置变更或需要强制刷新时调用。】 */
    public void clearCache() {
        cache.invalidateAll();
    }

    /**
     * 【获取当前缓存条目数。先执行 cleanUp() 使待淘汰的 TTL 条目生效，
     * 确保返回的 size 反映调用方实际会看到的数量。】
     */
    public int cacheSize() {
        // 【强制执行待处理的 TTL 淘汰，使 size 反映调用方实际会看到的数量】
        // English: Force pending TTL evictions to settle so size reflects what callers would see.
        cache.cleanUp();
        return (int) cache.estimatedSize();
    }

    /**
     * 【获取 LLM 服务的运行统计信息，包含总调用数、缓存命中数、成功/失败数、
     * 缓存大小、缓存开关状态，以及最近一次失败的详细信息。
     * 供 /admin/llm/stats 等管理端点使用。】
     */
    public Map<String, Object> stats() {
        cache.cleanUp();
        var m = new LinkedHashMap<String, Object>();
        m.put("totalCalls", totalCalls.get());
        m.put("cacheHits", cacheHits.get());
        m.put("successes", successes.get());
        m.put("failures", failures.get());
        m.put("cacheSize", (int) cache.estimatedSize());
        m.put("cacheEnabled", cacheEnabled);
        var f = lastFailure;
        if (f != null) {
            m.put("lastFailureProvider", f.provider);
            m.put("lastFailureMessage", f.message);
            m.put("lastFailureAt", f.timestamp);
        }
        return m;
    }

    // ----- JSON 提取 -----

    /**
     * 【从 LLM 文本响应中提取 JSON 对象。处理常见的非纯 JSON 输出场景：
     * <ul>
     *   <li>Markdown 围栏：去除开头的 ``` 和结尾的 ```</li>
     *   <li>混合文本：定位第一个 { 或 [，匹配对应的 } 或 ]，提取中间部分</li>
     * </ul>
     * 解析失败时返回 Optional.empty()，不抛异常。】
     */
    private Optional<JsonNode> extractJson(String text) {
        if (text == null) return Optional.empty();
        var trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            var firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        var start = Math.min(indexOrMax(trimmed, '{'), indexOrMax(trimmed, '['));
        if (start == Integer.MAX_VALUE) return Optional.empty();
        var open = trimmed.charAt(start);
        var close = open == '{' ? '}' : ']';
        var end = trimmed.lastIndexOf(close);
        if (end <= start) return Optional.empty();
        try {
            return Optional.of(mapper.readTree(trimmed.substring(start, end + 1)));
        } catch (Exception ex) {
            log.warn("LLM JSON parse failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** 【查找字符在字符串中首次出现的索引，未找到返回 Integer.MAX_VALUE】 */
    private int indexOrMax(String s, char c) {
        var i = s.indexOf(c);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** 【截断过长字符串，超过 300 字符时截断并添加 "..." 后缀】 */
    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /**
     * 【获取默认 provider 的基本信息（名称、可用状态、模型），
     * 供 /admin/llm/info 等管理端点展示。】
     */
    public Map<String, String> info() {
        var p = registry.getDefault();
        return Map.of(
                "provider", p == null ? "" : p.name(),
                "available", String.valueOf(isAvailable()),
                "model", p == null ? "" : p.model()
        );
    }

    /**
     * 【获取所有已注册 provider 的信息，包括类型、模型、可用状态和是否为默认 provider。
     * 供 /admin/llm/info 页面展示完整的 provider 列表。】
     *
     * <p>English: All registered providers — useful for /admin/llm/info pages.</p>
     */
    public Map<String, Map<String, String>> providers() {
        var out = new LinkedHashMap<String, Map<String, String>>();
        for (var p : registry.all()) {
            out.put(p.name(), Map.of(
                    "type", p.type(),
                    "model", p.model(),
                    "available", String.valueOf(p.available()),
                    "isDefault", String.valueOf(p.name().equals(registry.defaultName()))
            ));
        }
        return out;
    }

    /**
     * 【失败记录，用于记录最近一次 LLM 调用失败的详细信息。
     * 包含 provider 名称、截断的错误消息和失败时间戳。】
     */
    private record FailureRecord(String provider, String message, long timestamp) {}
}
