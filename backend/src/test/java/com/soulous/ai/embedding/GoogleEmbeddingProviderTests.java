package com.soulous.ai.embedding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Unit tests for {@link GoogleEmbeddingProvider}. We spin up a real {@link HttpServer}
 * on an ephemeral port so we can verify both URL/header construction (the part most
 * likely to break against the real API) and response parsing without depending on
 * Google's actual endpoint.
 *
 * 【GoogleEmbeddingProvider 的单元测试。
 *  在临时端口上启动真实 HTTP 服务器，验证：
 *  1. URL 路径构造正确（/v1beta/models/{model}:embedContent）
 *  2. 请求头包含 x-goog-api-key
 *  3. 请求体包含正确的 model、text、outputDimensionality 字段
 *  4. 响应解析为 float 数组
 *  5. 可用性检查、非 2xx 错误处理、空 embedding 异常、模型路径去重
 *  无需依赖 Google 真实端点。】
 */
class GoogleEmbeddingProviderTests {
    private HttpServer server;
    private int port;

    /**
     * 【每个测试前启动本地 HTTP 服务器】
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    /**
     * 【每个测试后停止 HTTP 服务器】
     */
    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String baseUrl() { return "http://127.0.0.1:" + port; }

    /**
     * 【测试 embed() 方法的完整请求/响应流程：
     *  验证请求路径为 /v1beta/models/text-embedding-004:embedContent，
     *  请求头包含正确的 API key，
     *  请求体包含 model、text、outputDimensionality 字段，
     *  响应中的 embedding values 被正确解析为 float 数组。】
     */
    @Test
    void embedSendsExpectedPathHeadersAndBodyAndParsesResponse() throws Exception {
        var captured = new AtomicReference<String>();
        var capturedPath = new AtomicReference<String>();
        var capturedApiKey = new AtomicReference<String>();
        server.createContext("/", exchange -> {
            capturedPath.set(exchange.getRequestURI().toString());
            capturedApiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.set(body);
            var resp = "{\"embedding\":{\"values\":[0.1, 0.2, -0.3, 0.4]}}";
            var bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var p = new GoogleEmbeddingProvider("google", "test-key", "text-embedding-004",
                baseUrl(), 4, 10);
        var vec = p.embed("hello world");

        assertThat(vec).hasSize(4);
        assertThat(vec[0]).isCloseTo(0.1f, org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(vec[3]).isCloseTo(0.4f, org.assertj.core.data.Offset.offset(1e-5f));

        assertThat(capturedPath.get()).isEqualTo("/v1beta/models/text-embedding-004:embedContent");
        assertThat(capturedApiKey.get()).isEqualTo("test-key");
        assertThat(captured.get())
                .contains("\"model\":\"models/text-embedding-004\"")
                .contains("\"text\":\"hello world\"")
                .contains("\"outputDimensionality\":4");
    }

    /**
     * 【测试已包含 models/ 前缀的模型路径不会被重复拼接。
     *  例如 "models/gemini-embedding-001" 的 URL 应为
     *  /v1beta/models/gemini-embedding-001:embedContent，
     *  而非 /v1beta/models/models/gemini-embedding-001:embedContent。】
     */
    @Test
    void acceptsFullyQualifiedModelPathWithoutDoublingPrefix() {
        var p = new GoogleEmbeddingProvider("google", "k", "models/gemini-embedding-001",
                baseUrl(), 768, 10);
        // url should be /v1beta/models/gemini-embedding-001:embedContent, NOT
        // /v1beta/models/models/gemini-embedding-001:embedContent
        assertThat(p.modelPath()).isEqualTo("models/gemini-embedding-001");
        assertThat(p.urlFor()).endsWith("/v1beta/models/gemini-embedding-001:embedContent");
    }

    /**
     * 【测试可用性检查：空 API key 时 available() 返回 false，
     *  有 API key 时返回 true。】
     */
    @Test
    void availableRequiresApiKey() {
        var noKey = new GoogleEmbeddingProvider("google", "", "text-embedding-004", baseUrl(), 768, 10);
        assertThat(noKey.available()).isFalse();
        var withKey = new GoogleEmbeddingProvider("google", "k", "text-embedding-004", baseUrl(), 768, 10);
        assertThat(withKey.available()).isTrue();
    }

    /**
     * 【测试非 2xx 响应（如 401）时抛出 RuntimeException，异常信息包含状态码。】
     */
    @Test
    void non2xxResponseSurfacesAsRuntimeException() {
        server.createContext("/", exchange -> {
            var body = "{\"error\":{\"code\":401,\"message\":\"API key invalid\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var p = new GoogleEmbeddingProvider("google", "bad", "text-embedding-004", baseUrl(), 768, 10);

        var ex = catchThrowable(() -> p.embed("x"));
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).contains("401");
    }

    /**
     * 【测试空 embedding values 数组时抛出 RuntimeException，异常信息包含 "missing or empty"。】
     */
    @Test
    void emptyEmbeddingValuesArrayThrows() {
        server.createContext("/", exchange -> {
            var body = "{\"embedding\":{\"values\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var p = new GoogleEmbeddingProvider("google", "k", "text-embedding-004", baseUrl(), 768, 10);

        var ex = catchThrowable(() -> p.embed("x"));
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).contains("missing or empty");
    }

    /**
     * 【测试空模型名时使用默认值 "text-embedding-004"，
     *  modelPath 为 "models/text-embedding-004"。】
     */
    @Test
    void defaultsModelWhenBlank() {
        var p = new GoogleEmbeddingProvider("google", "k", "", baseUrl(), 768, 10);
        assertThat(p.model()).isEqualTo("text-embedding-004");
        assertThat(p.modelPath()).isEqualTo("models/text-embedding-004");
    }
}
