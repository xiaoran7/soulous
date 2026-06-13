package com.soulous.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 【agent-service 边车客户端】
 *
 * <p>所有方法失败时返回 Optional.empty()（或静默跳过），调用方据此走本地降级路径——
 * agent 整体不可用时业务不受影响，这是与历史 Anima 集成最大的区别。</p>
 *
 * <p>SSE 解析：逐行读取 event:/data: 对，token 事件回调 onChunk，done 事件返回结构化终包。</p>
 */
@Service
public class AgentClient {
    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final AgentProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 【RAG 推送专用单线程池：best-effort，绝不阻塞业务事务】 */
    private final ExecutorService ragExecutor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "agent-rag-push");
        t.setDaemon(true);
        return t;
    });

    public AgentClient(AgentProperties props) {
        this.props = props;
        // 必须钉死 HTTP/1.1：默认 HTTP_2 会对明文地址发 h2c Upgrade 请求，
        // uvicorn 不支持协议升级，直接回 400 "Unsupported upgrade request"
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /** 【总开关 + 配置完整性检查】 */
    public boolean enabled() {
        return props.isEnabled() && props.getBaseUrl() != null && !props.getBaseUrl().isBlank();
    }

    // ----- 基础调用 -------------------------------------------------------

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + path))
                .header("Content-Type", "application/json")
                .header("X-Service-Token", props.getToken() == null ? "" : props.getToken());
    }

    /** 【单发 JSON POST。失败/非 200 返回 empty。】 */
    public Optional<JsonNode> postJson(String path, ObjectNode payload) {
        if (!enabled()) return Optional.empty();
        try {
            var req = request(path)
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("agent {} 返回 HTTP {}", path, resp.statusCode());
                return Optional.empty();
            }
            return Optional.of(mapper.readTree(resp.body()));
        } catch (Exception ex) {
            log.warn("agent {} 调用失败: {}", path, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 【SSE 流式 POST：token 事件文本回调 onChunk，返回 done 事件的结构化载荷。
     * error 事件或传输异常返回 empty（调用方走降级）。】
     */
    public Optional<JsonNode> postStream(String path, ObjectNode payload, Consumer<String> onChunk) {
        return postStream(path, payload, onChunk, null);
    }

    /**
     * 【SSE 流式 POST（带 status 透传）：status 事件（如 {"stage":"tool","name":"query_timetable"}）
     * 回调 onStatus，供上层把「正在调用工具」实时透给前端，避免流式停顿被误以为卡死。】
     */
    public Optional<JsonNode> postStream(String path, ObjectNode payload,
                                         Consumer<String> onChunk, Consumer<JsonNode> onStatus) {
        if (!enabled()) return Optional.empty();
        try {
            var req = request(path)
                    .timeout(Duration.ofSeconds(props.getStreamTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                log.warn("agent {} 返回 HTTP {}", path, resp.statusCode());
                return Optional.empty();
            }
            try (var reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String event = null;
                var data = new StringBuilder();
                String line;
                JsonNode done = null;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        event = line.substring(7).trim();
                    } else if (line.startsWith("data: ")) {
                        data.append(line.substring(6));
                    } else if (line.isEmpty() && event != null) {
                        var node = mapper.readTree(data.toString());
                        switch (event) {
                            case "token" -> {
                                var text = node.path("text").asText("");
                                if (!text.isEmpty() && onChunk != null) onChunk.accept(text);
                            }
                            case "status" -> {
                                if (onStatus != null) onStatus.accept(node);
                            }
                            case "done" -> done = node;
                            case "error" -> {
                                log.warn("agent {} 流内错误: {}", path, node.path("message").asText());
                                return Optional.empty();
                            }
                            default -> { }
                        }
                        event = null;
                        data.setLength(0);
                    }
                }
                return Optional.ofNullable(done);
            }
        } catch (Exception ex) {
            log.warn("agent {} 流式调用失败: {}", path, ex.getMessage());
            return Optional.empty();
        }
    }

    // ----- 业务封装 -------------------------------------------------------

    /** 【AI 拆解对话（流式）：返回 done 载荷 {reply, plan?, clarify?}；onStatus 透传工具调用状态（可空）】 */
    public Optional<JsonNode> chatStream(Long userId, Long conversationId, String message,
                                         Consumer<String> onChunk, Consumer<JsonNode> onStatus) {
        return postStream("/agent/chat/stream", chatPayload(userId, conversationId, message), onChunk, onStatus);
    }

    /** 【AI 拆解对话（非流式）】 */
    public Optional<JsonNode> chat(Long userId, Long conversationId, String message) {
        return postJson("/agent/chat", chatPayload(userId, conversationId, message));
    }

    private ObjectNode chatPayload(Long userId, Long conversationId, String message) {
        var payload = mapper.createObjectNode();
        payload.put("userId", String.valueOf(userId));
        payload.put("conversationId", String.valueOf(conversationId));
        payload.put("message", message);
        return payload;
    }

    /** 【任务凭证审核：payload 由调用方组装（ReviewRequest 契约），返回 ReviewVerdict JSON】 */
    public Optional<JsonNode> review(ObjectNode payload) {
        return postJson("/agent/review", payload);
    }

    /** 【每日复盘（非流式）】 */
    public Optional<JsonNode> dailyReview(ObjectNode payload) {
        return postJson("/agent/daily-review", payload);
    }

    /** 【每日复盘（流式叙述 + 结构化终包）】 */
    public Optional<JsonNode> dailyReviewStream(ObjectNode payload, Consumer<String> onChunk) {
        return postStream("/agent/daily-review/stream", payload, onChunk);
    }

    // ----- RAG 同步（best-effort，异步不阻塞业务） -------------------------

    public void ragUpsertAsync(Long userId, String sourceType, Long sourceId, String text) {
        if (!enabled()) return;
        ragExecutor.submit(() -> {
            var payload = mapper.createObjectNode();
            payload.put("userId", String.valueOf(userId));
            payload.put("sourceType", sourceType);
            payload.put("sourceId", sourceId);
            payload.put("text", text == null ? "" : text);
            postJson("/rag/upsert", payload);
        });
    }

    public void ragDeleteAsync(Long userId, String sourceType, Long sourceId) {
        if (!enabled()) return;
        ragExecutor.submit(() -> {
            var payload = mapper.createObjectNode();
            payload.put("userId", String.valueOf(userId));
            if (sourceType != null) payload.put("sourceType", sourceType);
            if (sourceId != null) payload.put("sourceId", sourceId);
            postJson("/rag/delete", payload);
        });
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
