package com.soulous.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 【Anima agent 服务的 HTTP 客户端。只认 Anima 的契约，失败一律降级为 empty，
 * 由上层给兜底/回退，绝不把异常抛给用户。】
 *
 * <p>仿 {@link com.soulous.ai.provider.OpenAiCompatibleProvider} 用 JDK {@link HttpClient}，
 * 不引入额外依赖。强制 HTTP/1.1：JDK 默认 HTTP/2 的 h2c 升级请求会被 uvicorn(h11) 丢掉请求体。</p>
 */
@Component
public class AnimaClient {
    private static final Logger log = LoggerFactory.getLogger(AnimaClient.class);

    private final CompanionProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnimaClient(CompanionProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())))
                .build();
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    public String personaId() {
        return props.getPersonaId();
    }

    /** 跑一轮对话，返回回复文本。失败返回 empty。 */
    public Optional<String> run(String userId, String sessionId, String userMessage) {
        var body = mapper.createObjectNode();
        body.put("user_message", userMessage);
        body.put("user_id", userId);
        body.put("session_id", sessionId);
        body.put("persona_id", props.getPersonaId());
        return postJson("/v1/agent/run", body)
                .map(n -> n.path("reply").asText(""))
                .filter(s -> !s.isBlank());
    }

    /** 宠物审核一份提交，返回结构化裁决 JSON(含 reply)。失败返回 empty(调用方据此回退)。 */
    public Optional<JsonNode> review(String userId, String sessionId, ObjectNode submission) {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        body.put("session_id", sessionId);
        body.put("persona_id", props.getPersonaId());
        body.set("submission", submission);
        return postJson("/v1/review", body);
    }

    /** 拉某会话最近消息(聊天框加载历史用)。返回 messages 数组节点。 */
    public Optional<JsonNode> sessionMessages(String sessionId, int limit) {
        var path = "/v1/session/messages?session_id="
                + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "&limit=" + limit;
        return getJson(path).map(n -> n.path("messages"));
    }

    /** 拉某用户的画像事实(宠物「记得」的结构化记忆)。返回 facts 数组节点。 */
    public Optional<JsonNode> profileFacts(String userId) {
        var path = "/v1/memory/profile?user_id="
                + URLEncoder.encode(userId, StandardCharsets.UTF_8);
        return getJson(path).map(n -> n.path("facts"));
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ── HTTP helpers ───────────────────────────────────────────

    private Optional<JsonNode> postJson(String path, JsonNode body) {
        var req = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(req, path);
    }

    private Optional<JsonNode> getJson(String path) {
        var req = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())))
                .GET()
                .build();
        return send(req, path);
    }

    private Optional<JsonNode> send(HttpRequest req, String path) {
        try {
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("Anima {} returned {}: {}", path, resp.statusCode(), resp.body());
                return Optional.empty();
            }
            return Optional.of(mapper.readTree(resp.body()));
        } catch (Exception ex) {
            log.warn("Anima {} call failed: {}", path, ex.getMessage());
            return Optional.empty();
        }
    }

    private String base() {
        return props.getBaseUrl().replaceAll("/+$", "");
    }
}
