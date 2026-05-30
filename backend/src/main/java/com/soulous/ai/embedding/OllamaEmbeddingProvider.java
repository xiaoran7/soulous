package com.soulous.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 【基于 Ollama 兼容 API 的 Embedding 实现。
 * 调用 Ollama 的 OpenAI 兼容 /v1/embeddings 端点（Ollama 默认配置即可提供）。
 * 本地部署无需 API 密钥；若在 Ollama 前置了认证代理，可传入密钥。
 *
 * 此类同时兼容 OpenAI / DeepSeek 等兼容端点——只需将 base-url 指向对应主机并配置 API 密钥。
 * 向量维度通过配置指定（非自动检测），以便在启动时就能确定数据库向量列的尺寸。】
 *
 * <p>Calls Ollama's OpenAI-compatible {@code /v1/embeddings} endpoint. Ollama serves this when
 * launched with the default config (no env var needed). Works without an API key for local
 * deployments; pass a key if you've put auth in front of Ollama.</p>
 *
 * <p>This same class also works against OpenAI / DeepSeek-compatible embedding endpoints —
 * just point {@code base-url} at the right host and supply an API key. Dimension is
 * configured (not auto-detected) so we can size the vector column without a startup probe.</p>
 */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {
    /** 【Ollama 本地服务的默认 base URL】 */
    private static final String DEFAULT_BASE = "http://localhost:11434/v1";

    private final String name;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int dimension;
    private final int timeoutSeconds;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 【构造函数：初始化 Ollama embedding provider。
     * 对 null 或空白参数做防御性处理——apiKey 为空字符串、model 默认 "nomic-embed-text"、
     * baseUrl 默认 localhost、dimension 默认 768、timeout 默认 30 秒。
     * 末尾斜杠会被清除以避免拼接 URL 时出现双斜杠。】
     */
    public OllamaEmbeddingProvider(String name, String apiKey, String model, String baseUrl,
                                   int dimension, int timeoutSeconds) {
        this.name = name;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = (model == null || model.isBlank()) ? "nomic-embed-text" : model.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? DEFAULT_BASE
                : baseUrl.trim().replaceAll("/+$", "");
        // nomic-embed-text is 768-dim by default
        // 【nomic-embed-text 模型默认输出 768 维向量】
        this.dimension = dimension <= 0 ? 768 : dimension;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(this.timeoutSeconds)).build();
    }

    @Override public String name() { return name; }
    @Override public String model() { return model; }
    @Override public int dimension() { return dimension; }

    /**
     * 【判断 provider 是否可用。
     * 有 API 密钥时直接返回 true；无密钥时仅允许连接本地地址（localhost / 127.0.0.1 / 0.0.0.0），
     * 防止无密钥请求发送到公网造成安全风险。】
     *
     * <p>Local Ollama doesn't need an API key.</p>
     */
    @Override
    public boolean available() {
        // Local Ollama doesn't need an API key.
        if (!apiKey.isBlank()) return true;
        var lower = baseUrl.toLowerCase();
        return lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("0.0.0.0");
    }

    /**
     * 【将单段文本转换为浮点向量。
     * 构建 OpenAI 兼容的 /v1/embeddings 请求，携带 model 和 input 字段。
     * 有 API 密钥时附加 Bearer 认证头。
     * 解析响应中 data[0].embedding 数组，逐元素转为 float。
     * HTTP 非 2xx 或响应格式异常时抛出 RuntimeException。】
     *
     * @param text 【待嵌入的文本内容，null 会被当作空字符串处理】
     * @return 【浮点数组表示的向量，维度与配置的 dimension 一致】
     * @throws Exception 【网络错误或服务端返回非 2xx 状态码时抛出】
     *
     * <p>Embed a single piece of text. May throw on network/HTTP errors — caller decides fallback.</p>
     */
    @Override
    public float[] embed(String text) throws Exception {
        var url = baseUrl + "/embeddings";
        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", text == null ? "" : text);

        var builder = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) builder.header("authorization", "Bearer " + apiKey);

        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Embedding(" + name + ") " + response.statusCode()
                    + ": " + truncate(response.body()));
        }
        var json = mapper.readTree(response.body());
        var data = json.path("data");
        if (!data.isArray() || data.size() == 0) {
            throw new RuntimeException("Embedding(" + name + "): empty data");
        }
        var arr = data.get(0).path("embedding");
        if (!arr.isArray()) {
            throw new RuntimeException("Embedding(" + name + "): missing embedding array");
        }
        var vec = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) vec[i] = (float) arr.get(i).asDouble();
        return vec;
    }

    /**
     * 【截断过长的错误响应体，避免日志中出现大段 HTML/JSON。
     * 超过 300 字符时截断并追加 "..."。】
     */
    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
