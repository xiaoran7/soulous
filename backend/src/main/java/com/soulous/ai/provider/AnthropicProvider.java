package com.soulous.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 【Anthropic Claude 大语言模型的提供商实现。
 *
 * <p>通过 HTTP 调用 Anthropic Messages API ({@code /v1/messages}) 发送提示词并获取补全结果。
 * 支持自定义 API 密钥、模型名称、基础 URL 和超时时间。当 API 密钥为空时，
 * {@link #available()} 返回 {@code false}，调用方可据此降级到规则引擎等备选逻辑。</p>
 *
 * <p>该类实现了 {@link LlmProvider} 接口，与 {@link MockProvider} 共同构成策略模式的
 * 多提供商架构，上层服务通过统一接口调用，无需关心底层是真实 API 还是占位实现。</p>
 */
public final class AnthropicProvider implements LlmProvider {
    /** 【Anthropic 官方 API 的默认基础地址】 */
    private static final String DEFAULT_BASE = "https://api.anthropic.com";

    /** 【提供商实例的可读名称，用于日志和错误消息中区分不同实例】 */
    private final String name;

    /** 【Anthropic API 密钥，用于请求头 x-api-key 鉴权；为空时 provider 不可用】 */
    private final String apiKey;

    /** 【要使用的 Claude 模型标识符，默认为 claude-haiku-4-5-20251001】 */
    private final String model;

    /** 【API 基础地址，为空时使用 DEFAULT_BASE；支持自定义以兼容代理或私有部署】 */
    private final String baseUrl;

    /** 【HTTP 请求超时时间（秒），不大于 0 时默认为 30 秒】 */
    private final int timeoutSeconds;

    /** 【复用的 HttpClient 实例，配置了连接超时，线程安全可多次发送请求】 */
    private final HttpClient http;

    /** 【Jackson ObjectMapper，用于序列化请求体和解析响应 JSON】 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 【构造函数：初始化 Anthropic 提供商实例。
     *
     * <p>对所有参数做防御性处理：apiKey/model/baseUrl 为 null 时使用安全默认值，
     * timeoutSeconds 不合法时回退到 30 秒。</p>
     *
     * @param name          【提供商实例名称，用于标识和日志】
     * @param apiKey        【Anthropic API 密钥，null 或空白时 provider 不可用】
     * @param model         【模型标识符，null 或空白时使用默认模型 claude-haiku-4-5-20251001】
     * @param baseUrl       【API 基础地址，null 时使用官方地址，末尾斜杠自动去除】
     * @param timeoutSeconds【HTTP 超时秒数，不大于 0 时默认 30】
     */
    public AnthropicProvider(String name, String apiKey, String model, String baseUrl, int timeoutSeconds) {
        this.name = name;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = (model == null || model.isBlank()) ? "claude-haiku-4-5-20251001" : model.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(this.timeoutSeconds)).build();
    }

    /** 【返回提供商实例名称】 */
    @Override public String name() { return name; }

    /** 【返回提供商类型标识 "anthropic"，用于按类型路由或配置匹配】 */
    @Override public String type() { return "anthropic"; }

    /** 【返回当前使用的模型标识符】 */
    @Override public String model() { return model; }

    /** 【判断此提供商是否可用——API 密钥非空白时才可用】 */
    @Override public boolean available() { return !apiKey.isBlank(); }

    /**
     * 【调用 Anthropic Messages API 获取模型补全结果。
     *
     * <p>业务流程：</p>
     * <ol>
     *   <li>拼接完整的 API 端点 URL</li>
     *   <li>构建 JSON 请求体，包含 model、max_tokens(1024)、可选的 system prompt 和 user 消息</li>
     *   <li>通过 HttpClient 发送 POST 请求，附带 API 密钥和版本头</li>
     *   <li>校验 HTTP 响应状态码，非 2xx 时抛出异常</li>
     *   <li>从响应 JSON 的 {@code content[0].text} 中提取文本结果</li>
     * </ol>
     *
     * @param systemPrompt 【系统提示词，设定模型的行为角色和约束；可为 null 或空白】
     * @param userPrompt   【用户提示词，即实际要处理的内容】
     * @return 【模型生成的文本补全结果；响应结构异常时返回空字符串】
     * @throws Exception   【HTTP 请求失败、响应状态码非 2xx、JSON 解析错误时抛出 RuntimeException】
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) throws Exception {
        var url = (baseUrl.isBlank() ? DEFAULT_BASE : baseUrl) + "/v1/messages";
        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 1024);
        if (systemPrompt != null && !systemPrompt.isBlank()) body.put("system", systemPrompt);
        var messages = body.putArray("messages");
        var msg = (ObjectNode) messages.addObject();
        msg.put("role", "user");
        msg.put("content", userPrompt);

        var request = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Anthropic(" + name + ") " + response.statusCode() + ": " + truncate(response.body()));
        }
        var json = mapper.readTree(response.body());
        var content = json.path("content");
        if (content.isArray() && content.size() > 0) {
            return content.get(0).path("text").asText("");
        }
        return "";
    }

    /**
     * 【截断过长字符串，用于错误消息中避免日志膨胀。
     * 超过 300 字符时截取前 300 字符并追加 "..."。】
     *
     * @param s 【待截断的原始字符串，可为 null】
     * @return 【截断后的字符串；null 输入返回空字符串】
     */
    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
