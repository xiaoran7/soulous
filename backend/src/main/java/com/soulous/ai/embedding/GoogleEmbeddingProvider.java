package com.soulous.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 【Google Generative Language API 的 Embedding Provider 实现。
 * 通过 HTTP 调用 Google 的 embedContent 端点，将文本转换为浮点向量。
 *
 * <p>【设计要点：
 * <ul>
 *   <li>API 端点格式为 POST {base-url}/v1beta/models/{model}:embedContent，
 *       与 OpenAI/Ollama 的 /v1/embeddings 路径不同，因此不能复用 OllamaEmbeddingProvider</li>
 *   <li>使用 x-goog-api-key header 传递认证密钥（而非 OpenAI 的 Bearer token）</li>
 *   <li>model 配置支持裸 slug（如 text-embedding-004）和完整路径（如 models/text-embedding-004），
 *       内部会自动规范化</li>
 *   <li>outputDimensionality 参数仅对支持维度截断的模型有意义
 *       （gemini-embedding-001 支持 768/1536/3072；text-embedding-004 固定 768），
 *       发送此参数对不支持的模型无害</li>
 *   <li>base-url 默认为 https://generativelanguage.googleapis.com，
 *       可通过配置覆盖以使用代理或 VPN</li>
 * </ul>
 * </p>
 *
 * <p>English: Calls Google's Generative Language API embedding endpoint:
 *
 * <pre>
 *   POST {base-url}/v1beta/models/{model}:embedContent
 *   Header: x-goog-api-key: {key}
 *   Body:   {"content":{"parts":[{"text":"..."}]} ,"outputDimensionality":{dim}?}
 *   Response: {"embedding":{"values":[...]}}
 * </pre>
 *
 * Unlike OpenAI / Ollama, Gemini's API uses a model-prefixed path and a different
 * request shape, so we can't reuse {@link OllamaEmbeddingProvider}. The {@code model}
 * config can be a bare slug ({@code text-embedding-004}) or the fully-qualified form
 * ({@code models/text-embedding-004}); we normalize both. {@code outputDimensionality}
 * is only included for models that support truncation (gemini-embedding-001 supports
 * 768/1536/3072; text-embedding-004 is fixed 768 — sending the field is harmless).
 *
 * {@code base-url} defaults to {@code https://generativelanguage.googleapis.com} so
 * a proxy / VPN can be slotted in by overriding it.</p>
 */
public final class GoogleEmbeddingProvider implements EmbeddingProvider {
    /** 【Google Generative Language API 的默认基础地址】 */
    private static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com";

    /** 【默认使用的 embedding 模型】 */
    private static final String DEFAULT_MODEL = "text-embedding-004";

    /** 【provider 逻辑名称，用于日志和路由标识】 */
    private final String name;

    /** 【Google API 认证密钥，通过 x-goog-api-key header 传递】 */
    private final String apiKey;

    /** 【模型裸 slug，如 "text-embedding-004"，用于 JSON body 中的 model 字段】 */
    private final String model;        // bare slug, e.g. "text-embedding-004"

    /** 【模型完整路径，如 "models/text-embedding-004"，用于 URL 路径和 JSON body】 */
    private final String modelPath;    // fully-qualified, e.g. "models/text-embedding-004"

    /** 【API 基础 URL，可配置以支持代理或 VPN】 */
    private final String baseUrl;

    /** 【embedding 向量维度。text-embedding-004 固定 768；gemini-embedding-001 支持 768/1536/3072】 */
    private final int dimension;

    /** 【请求超时时间（秒）】 */
    private final int timeoutSeconds;

    /** 【复用的 HttpClient 实例】 */
    private final HttpClient http;

    /** 【Jackson JSON 序列化器】 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 【构造函数：初始化 Google Embedding Provider 的所有配置参数。
     * 自动规范化 model 名称，确保不会出现 "models/models/..." 的双重前缀问题。】
     *
     * @param name          【provider 逻辑名称】
     * @param apiKey        【Google API 认证密钥】
     * @param model         【模型名称，支持裸 slug 或 models/ 前缀格式，null 时使用默认模型】
     * @param baseUrl       【API 基础 URL，null 或空白时使用 Google 默认地址】
     * @param dimension     【向量维度，小于等于 0 时默认 768】
     * @param timeoutSeconds【超时时间（秒），小于等于 0 时默认 30】
     */
    public GoogleEmbeddingProvider(String name, String apiKey, String model, String baseUrl,
                                   int dimension, int timeoutSeconds) {
        this.name = name;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        var rawModel = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        // 【接受 "text-embedding-004" 或 "models/text-embedding-004" 两种格式，
        // 规范化为裸 slug，避免 URL 构建时出现 "models/models/..." 的双重前缀。】
        // English: Accept either "text-embedding-004" or "models/text-embedding-004" — normalize so
        // the URL builder doesn't accidentally write "models/models/...".
        this.model = rawModel.startsWith("models/") ? rawModel.substring("models/".length()) : rawModel;
        this.modelPath = "models/" + this.model;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? DEFAULT_BASE
                : baseUrl.trim().replaceAll("/+$", "");
        // 【text-embedding-004 固定 768 维；gemini-embedding-001 支持 768/1536/3072】
        // English: text-embedding-004 is fixed 768; gemini-embedding-001 supports 768/1536/3072.
        this.dimension = dimension <= 0 ? 768 : dimension;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(this.timeoutSeconds)).build();
    }

    @Override public String name() { return name; }
    @Override public String model() { return model; }
    @Override public int dimension() { return dimension; }

    /**
     * 【检查此 provider 是否可用。Google API 始终需要认证密钥，
     * 没有 key 就无法探测可用性，因此仅在 apiKey 非空时返回 true。】
     *
     * <p>English: Google API always requires a key — without one, we can't even probe availability.</p>
     */
    @Override public boolean available() { return !apiKey.isBlank(); }

    /**
     * 【将文本转换为 Google embedding 向量。构建符合 Google Generative Language API 格式的
     * JSON 请求体，通过 x-goog-api-key header 认证，解析响应中的 embedding.values 数组。
     *
     * <p>请求体结构：
     * <pre>
     * {
     *   "model": "models/text-embedding-004",
     *   "content": { "parts": [{ "text": "输入文本" }] },
     *   "outputDimensionality": 768  // 仅对支持截断的模型有意义
     * }
     * </pre>
     * </p>
     *
     * @param text 【要向量化的文本】
     * @return 【浮点向量数组】
     * @throws Exception 【HTTP 请求失败、非 2xx 响应、或响应中缺少 embedding.values 时抛出】
     */
    @Override
    public float[] embed(String text) throws Exception {
        var url = baseUrl + "/v1beta/" + modelPath + ":embedContent";

        var body = mapper.createObjectNode();
        body.put("model", modelPath);
        var parts = mapper.createArrayNode();
        var part = mapper.createObjectNode();
        part.put("text", text == null ? "" : text);
        parts.add(part);
        var content = mapper.createObjectNode();
        content.set("parts", parts);
        body.set("content", content);
        // 【仅对支持维度截断的模型有意义；对固定维度模型无害】
        // English: Only meaningful for models that support dimension truncation; harmless for fixed-dim models.
        if (dimension > 0) body.put("outputDimensionality", dimension);

        var request = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        // 【非 2xx 响应视为错误】
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Embedding(" + name + ") " + response.statusCode()
                    + ": " + truncate(response.body()));
        }
        var json = mapper.readTree(response.body());
        var arr = json.path("embedding").path("values");
        if (!arr.isArray() || arr.size() == 0) {
            throw new RuntimeException("Embedding(" + name + "): missing or empty embedding.values");
        }
        var vec = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) vec[i] = (float) arr.get(i).asDouble();
        return vec;
    }

    /**
     * 【包级别可见方法：返回构建好的完整 URL，供单元测试验证 URL 构造逻辑，
     * 无需实际发起 HTTP 请求。】
     *
     * <p>English: package-private — exposed for the unit test to verify URL construction without a real call.</p>
     */
    String urlFor() { return baseUrl + "/v1beta/" + modelPath + ":embedContent"; }

    /** 【返回规范化后的模型完整路径（如 "models/text-embedding-004"），供测试验证】 */
    String modelPath() { return modelPath; }

    /** 【截断过长字符串，超过 300 字符时截断并添加 "..." 后缀，用于错误信息展示】 */
    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /**
     * 【URL 编码工具方法，当前未使用但保留以备将来需要对查询参数编码时使用。】
     */
    @SuppressWarnings("unused")
    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
