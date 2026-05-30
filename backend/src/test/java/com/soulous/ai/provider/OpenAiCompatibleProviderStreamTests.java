package com.soulous.ai.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Streaming SSE parser tests for {@link OpenAiCompatibleProvider#stream}. We spin a real
 * HTTP server that emits an OpenAI/DeepSeek-style stream so we can exercise the actual
 * BufferedReader / data-line parsing path without hitting a real API.
 *
 * 【OpenAiCompatibleProvider 流式 SSE 解析器的单元测试。
 *  在本地启动真实 HTTP 服务器模拟 OpenAI/DeepSeek 风格的 SSE 流，
 *  验证 BufferedReader 和 data 行解析路径，无需调用真实 API。
 *  覆盖场景：正常分块接收、跳过注释/畸形行、非 2xx 错误处理、
 *  流式支持标志、连接中途关闭时已接收数据的完整性。】
 */
class OpenAiCompatibleProviderStreamTests {
    private HttpServer server;
    private int port;

    /**
     * 【每个测试前启动本地 HTTP 服务器，使用随机可用端口】
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    /**
     * 【每个测试后停止 HTTP 服务器，释放端口资源】
     */
    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String baseUrl() { return "http://127.0.0.1:" + port; }

    /**
     * 【配置服务器以 SSE 格式返回指定行，content-type 为 text/event-stream】
     */
    private void respondWithStream(String... lines) {
        server.createContext("/", exchange -> {
            var body = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    /**
     * 【测试正常流式接收：服务器发送 3 个 delta 块和 [DONE] 标记，
     *  验证每个 chunk 回调收到独立的 delta 内容，
     *  最终累积文本为 "Hello world!"。】
     */
    @Test
    void streamFiresOnChunkForEachDeltaAndReturnsAccumulatedText() throws Exception {
        respondWithStream(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"!\"}}]}",
                "",
                "data: [DONE]",
                ""
        );
        var p = new OpenAiCompatibleProvider("deepseek", "k", "deepseek-chat", baseUrl(), 10);

        var chunks = new ArrayList<String>();
        var full = p.stream("sys", "user", chunks::add);

        assertThat(chunks).containsExactly("Hello", " world", "!");
        assertThat(full).isEqualTo("Hello world!");
    }

    /**
     * 【测试流式解析跳过 SSE 注释（:开头）和畸形 JSON 行。
     *  注释行和无法解析的 data 行被忽略，
     *  无 content 字段的 delta 也被跳过，
     *  只返回有效的 "A" 和 "B" 块。】
     */
    @Test
    void streamSkipsKeepAlivesAndMalformedLines() throws Exception {
        respondWithStream(
                ": this is a comment",
                "data: not json at all",
                "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{}}]}",   // no content field — skip
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}",
                "",
                "data: [DONE]"
        );
        var p = new OpenAiCompatibleProvider("deepseek", "k", "deepseek-chat", baseUrl(), 10);

        var chunks = new ArrayList<String>();
        var full = p.stream("sys", "user", chunks::add);

        assertThat(chunks).containsExactly("A", "B");
        assertThat(full).isEqualTo("AB");
    }

    /**
     * 【测试非 2xx 响应时抛出 RuntimeException。
     *  服务器返回 401 错误，验证异常包含状态码 "401"，
     *  且未收到任何 chunk。】
     */
    @Test
    void streamErrorsOutOnNon2xx() {
        server.createContext("/", exchange -> {
            var body = "{\"error\":{\"message\":\"bad key\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var p = new OpenAiCompatibleProvider("deepseek", "bad", "deepseek-chat", baseUrl(), 10);

        List<String> chunks = new ArrayList<>();
        var ex = catchThrowable(() -> p.stream("sys", "user", chunks::add));
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).contains("401");
        assertThat(chunks).isEmpty();
    }

    /**
     * 【测试 supportsStreaming() 方法返回 true，
     *  确认 OpenAiCompatibleProvider 声明支持流式输出。】
     */
    @Test
    void supportsStreamingIsTrue() {
        var p = new OpenAiCompatibleProvider("openai", "k", "gpt-4o-mini", "https://api.openai.com", 10);
        assertThat(p.supportsStreaming()).isTrue();
    }

    /**
     * 【测试流式传输中途连接关闭时的数据完整性。
     *  服务器发送 2 个正常帧后直接关闭（不发送 [DONE]），
     *  验证已接收的 chunk 仍然完整返回（"part-1 " 和 "part-2"），
     *  BufferedReader.readLine 遇到 EOF 返回 null 不会导致数据丢失。】
     */
    @Test
    void streamDeliversChunksBeforeMidStreamHttpClose() throws Exception {
        // Server starts emitting normal SSE frames, then closes the connection abruptly
        // before sending [DONE]. The provider should still surface the chunks it already
        // accepted (caller can then preserve them); the call returns the accumulated text.
        // 【服务器开始发送正常 SSE 帧，然后在发送 [DONE] 之前突然关闭连接。
        //  提供者应仍然返回已接收的 chunk，调用方可保留这些数据。】
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            // Length 0 = chunked; we write a few frames then close mid-stream.
            // 【长度 0 = 分块传输；写入几帧后中途关闭】
            exchange.sendResponseHeaders(200, 0);
            var os = exchange.getResponseBody();
            os.write(("data: {\"choices\":[{\"delta\":{\"content\":\"part-1 \"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(("data: {\"choices\":[{\"delta\":{\"content\":\"part-2\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            exchange.close(); // hard close — no [DONE]
        });
        var p = new OpenAiCompatibleProvider("deepseek", "k", "deepseek-chat", baseUrl(), 10);

        var chunks = new ArrayList<String>();
        var full = p.stream("sys", "user", chunks::add);

        // BufferedReader.readLine handles the abrupt EOF gracefully — returning null —
        // so we just get the chunks that did arrive. Caller (PlanningSessionService)
        // then decides whether to treat short output as a partial.
        // 【BufferedReader.readLine 优雅处理 EOF（返回 null），
        //  只返回已到达的 chunk。调用方（PlanningSessionService）决定是否将短输出视为部分结果。】
        assertThat(chunks).containsExactly("part-1 ", "part-2");
        assertThat(full).isEqualTo("part-1 part-2");
    }
}
