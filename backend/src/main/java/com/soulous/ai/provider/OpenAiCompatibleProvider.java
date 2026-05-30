package com.soulous.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 【OpenAI Chat Completions 协议兼容的 LLM Provider 实现。
 * 通过 HTTP 调用 OpenAI 格式的 /v1/chat/completions 端点，支持同步和流式两种调用模式。】
 *
 * <p>【设计思路：采用"OpenAI 兼容"策略，因为国内主流 LLM（DeepSeek、Moonshot、通义等）和
 * 本地部署工具（Ollama、LM Studio）均提供 OpenAI 兼容 API，只需切换 base-url 和 api-key
 * 即可接入不同后端，无需为每个供应商编写独立实现。】</p>
 *
 * <p>【本地端点（如 Ollama）通常不需要 API key，系统会在 base-url 包含 localhost/127.0.0.1
 * 时自动放宽 key 校验。】</p>
 *
 * <p>English: OpenAI Chat Completions wire format. Covers:
 *   - OpenAI (default base https://api.openai.com)
 *   - DeepSeek, Moonshot, 通义, etc. via custom base-url
 *   - Ollama via base-url=http://localhost:11434/v1 (it serves an OpenAI-compatible endpoint)
 *
 * Local providers (e.g. Ollama) may not require an API key; we treat blank key as available
 * when base-url is non-default localhost.</p>
 */
public final class OpenAiCompatibleProvider implements LlmProvider {
    /** 【OpenAI 官方 API 的默认基础地址】 */
    private static final String DEFAULT_BASE = "https://api.openai.com";

    /** 【provider 逻辑名称，用于日志和路由标识，如 "deepseek"、"openai"】 */
    private final String name;

    /** 【API 认证密钥，通过 Bearer token 方式传递。本地端点可为空字符串】 */
    private final String apiKey;

    /** 【使用的模型标识符，如 "gpt-4o-mini"、"deepseek-chat" 等】 */
    private final String model;

    /** 【API 基础 URL，末尾斜杠会被自动去除。为空时使用 DEFAULT_BASE】 */
    private final String baseUrl;

    /** 【请求超时时间（秒），同时用于 HttpClient 连接超时和请求生命周期超时】 */
    private final int timeoutSeconds;

    /** 【复用的 HttpClient 实例，避免每次请求都创建新的连接】 */
    private final HttpClient http;

    /** 【Jackson JSON 序列化/反序列化器，线程安全，复用同一实例】 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 【构造函数：初始化 OpenAI 兼容 provider 的所有配置参数】
     *
     * @param name          【provider 逻辑名称，用于日志和路由】
     * @param apiKey        【API 认证密钥，null 会被转为空字符串】
     * @param model         【模型标识符，null 或空白时默认使用 "gpt-4o-mini"】
     * @param baseUrl       【API 基础 URL，null 时默认使用 OpenAI 官方地址，末尾斜杠自动去除】
     * @param timeoutSeconds【超时时间（秒），小于等于 0 时默认 30 秒】
     */
    public OpenAiCompatibleProvider(String name, String apiKey, String model, String baseUrl, int timeoutSeconds) {
        this.name = name;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = (model == null || model.isBlank()) ? "gpt-4o-mini" : model.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(this.timeoutSeconds)).build();
    }

    @Override public String name() { return name; }
    @Override public String type() { return "openai"; }
    @Override public String model() { return model; }

    /**
     * 【检查此 provider 是否可用。本地端点（Ollama / LM Studio）不需要 API key，
     * 因此当 base-url 包含 localhost、127.0.0.1 或 0.0.0.0 时，即使 apiKey 为空也视为可用。】
     *
     * <p>English: Local endpoints (Ollama / LM Studio) don't need a key.</p>
     */
    @Override
    public boolean available() {
        // 【本地端点（Ollama / LM Studio）不需要 API key】
        // English: Local endpoints (Ollama / LM Studio) don't need a key.
        if (!apiKey.isBlank()) return true;
        var lower = baseUrl.toLowerCase();
        return lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("0.0.0.0");
    }

    /**
     * 【同步阻塞式完成请求。构建 OpenAI Chat Completions 格式的 JSON body，发送 HTTP POST 请求，
     * 解析响应中 choices[0].message.content 作为返回结果。
     *
     * <p>设计要点：
     * <ul>
     *   <li>system prompt 仅在非空时才加入 messages 数组，避免发送无意义的系统消息</li>
     *   <li>API key 为空时不发送 Authorization header，兼容本地端点</li>
     *   <li>非 2xx 响应会抛出 RuntimeException，包含截断后的错误信息以便调试</li>
     * </ul>
     * </p>
     *
     * @param systemPrompt 【系统提示词，定义 AI 的角色和行为约束，可为 null 或空】
     * @param userPrompt   【用户提示词，即实际的问题或指令】
     * @return 【LLM 生成的文本回复，当 choices 数组为空时返回空字符串】
     * @throws Exception   【HTTP 请求失败、非 2xx 响应、或 JSON 解析异常时抛出】
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) throws Exception {
        return complete(systemPrompt, userPrompt, false);
    }

    /**
     * 【JSON 模式完成。与 {@link #complete(String, String)} 相同，但 jsonMode 为 true 时
     * 在请求体加入 {@code response_format:{"type":"json_object"}}，让 OpenAI/DeepSeek 等后端
     * 在协议层强制返回合法 JSON，几乎杜绝"口头声称已输出却没给 JSON""夹带 Markdown 围栏"
     * 这类问题。本地端点（Ollama 等）若不识别该字段会忽略它，不影响兼容性。】
     *
     * <p>English: When {@code jsonMode} is true, attach {@code response_format} so the backend enforces
     * valid JSON at the protocol level. Endpoints that don't recognise the field simply ignore it.</p>
     */
    @Override
    public String complete(String systemPrompt, String userPrompt, boolean jsonMode) throws Exception {
        var base = baseUrl.isBlank() ? DEFAULT_BASE : baseUrl;
        var url = base + "/v1/chat/completions";
        var body = mapper.createObjectNode();
        body.put("model", model);
        if (jsonMode) {
            body.set("response_format", mapper.createObjectNode().put("type", "json_object"));
        }
        var messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            var sys = (ObjectNode) messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        var user = (ObjectNode) messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);

        var builder = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        // 【API key 非空时才添加 Bearer 认证头，兼容本地端点无需认证的场景】
        if (!apiKey.isBlank()) builder.header("authorization", "Bearer " + apiKey);
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        // 【非 2xx 响应视为错误，抛出包含状态码和截断错误信息的异常】
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("OpenAI(" + name + ") " + response.statusCode() + ": " + truncate(response.body()));
        }
        var json = mapper.readTree(response.body());
        var choices = json.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            return choices.get(0).path("message").path("content").asText("");
        }
        return "";
    }

    /**
     * 【声明支持流式传输（SSE）。调用方据此决定使用 stream() 还是 complete()】
     *
     * <p>English: supportsStreaming capability flag.</p>
     */
    @Override
    public boolean supportsStreaming() { return true; }

    /**
     * 【流式完成请求。通过 SSE（Server-Sent Events）逐步接收 LLM 生成的文本片段，
     * 每收到一个 chunk 就通过 onChunk 回调实时推送给调用方，实现打字机效果的 UI 体验。
     *
     * <p>设计要点：
     * <ul>
     *   <li>超时时间至少为 300 秒（5 分钟），因为长推理链可能需要较长时间，
     *       旧版 30 秒超时会导致用户看到半截回复后报错"AI 暂不可用"</li>
     *   <li>SSE 协议格式为 "data: {json}"，终止标记为 "data: [DONE]"</li>
     *   <li>畸形行（keep-alive、注释等）会被静默跳过，保证流的健壮性</li>
     *   <li>返回完整累积文本，调用方无需自行拼接</li>
     * </ul>
     * </p>
     *
     * @param systemPrompt 【系统提示词】
     * @param userPrompt   【用户提示词】
     * @param onChunk      【每收到一个增量文本片段时的回调函数，用于实时推送到前端】
     * @return 【LLM 生成的完整累积文本】
     * @throws Exception   【HTTP 请求失败、非 2xx 响应时抛出】
     */
    @Override
    public String stream(String systemPrompt, String userPrompt, Consumer<String> onChunk) throws Exception {
        var base = baseUrl.isBlank() ? DEFAULT_BASE : baseUrl;
        var url = base + "/v1/chat/completions";
        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        var messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            var sys = (ObjectNode) messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        var user = (ObjectNode) messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);

        // 【流式传输的超时时间至少 300 秒。HttpClient 的 .timeout() 是整个请求生命周期的总超时，
        // 旧版 30 秒上限会在长推理链中途截断连接，导致用户看到半截回复后报"AI 暂不可用"。
        // 5 分钟足以覆盖任何实际推理链长度，同时避免真正挂起的上游连接永远占用 HTTP 线程。
        // 超时前已捕获的部分内容由 PlanningSessionService 保留。】
        // English: HttpClient's .timeout() is the total request lifecycle, so the old 30s cap was
        // killing legitimate long thoughtful replies mid-stream (the user reported seeing
        // half a reply then "AI 暂不可用"). For streaming we extend the cap to at minimum
        // 5 minutes (clamping the configured timeoutSeconds upward) — long enough for any
        // realistic reasoning chain, short enough that a truly hung upstream still gets
        // cleaned up instead of leaking the HTTP thread forever. Partial content captured
        // before timeout is preserved by PlanningSessionService.
        var streamTimeoutSeconds = Math.max(timeoutSeconds, 300);
        var builder = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .timeout(Duration.ofSeconds(streamTimeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) builder.header("authorization", "Bearer " + apiKey);

        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            var err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("OpenAI(" + name + ") " + response.statusCode() + ": " + truncate(err));
        }

        var full = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            // 【解析 OpenAI/DeepSeek 风格的 SSE 流：每行格式为 "data: {json}"，
            // 终止标记为 "data: [DONE]"，事件之间用空行分隔。
            // 从每个 JSON payload 中提取 choices[0].delta.content 字段作为增量文本。】
            // English: OpenAI/DeepSeek-style SSE: lines like "data: {json}", terminator "data: [DONE]",
            // and blank lines between events. We only care about the data: payloads and pull
            // choices[0].delta.content out of each.
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || !line.startsWith("data:")) continue;
                var payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                try {
                    var node = mapper.readTree(payload);
                    var delta = node.path("choices").path(0).path("delta").path("content");
                    if (delta.isTextual()) {
                        var chunk = delta.asText();
                        if (!chunk.isEmpty()) {
                            full.append(chunk);
                            onChunk.accept(chunk);
                        }
                    }
                } catch (Exception ignored) {
                    // 【跳过格式异常的行——某些 provider 会发送 keep-alive 或注释行】
                    // English: Skip malformed lines — some providers emit keep-alives or comments.
                }
            }
        }
        return full.toString();
    }

    /**
     * 【截断过长字符串，用于错误信息展示。超过 300 字符时截断并添加 "..." 后缀，
     * 避免日志中出现大量 API 响应内容。】
     */
    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
