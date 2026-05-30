package com.soulous.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/**
 * 【每日复盘控制器】
 * 提供每日学习复盘的 HTTP 接口，支持同步和流式（SSE）两种响应模式。
 * 复盘内容包括学习亮点、风险提示和明日建议。
 */
@RestController
@RequestMapping("/api/ai")
class DailyReviewController extends BaseController {
    /**
     * 【流式响应专用 ObjectMapper】
     * 用于将 token 数据序列化为 JSON 字符串，通过 SSE 事件推送给客户端。
     */
    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper();

    /** 【每日复盘服务】负责生成复盘内容的核心业务逻辑。 */
    private final DailyReviewService reviews;

    /**
     * 【构造器】注入用户服务和复盘服务。
     *
     * @param users   用户服务（来自 BaseController）
     * @param reviews 每日复盘服务
     */
    DailyReviewController(UserService users, DailyReviewService reviews) {
        super(users);
        this.reviews = reviews;
    }

    /**
     * 【同步复盘接口】一次性返回完整的每日复盘结果。
     * POST /api/ai/daily-review
     *
     * @param request HTTP 请求（需要用户认证）
     * @return 完整的复盘数据 Map
     */
    @PostMapping("/daily-review")
    Object dailyReview(HttpServletRequest request) {
        return reviews.generate(current(request));
    }

    /**
     * 【流式复盘接口】
     * 使用 Server-Sent Events (SSE) 逐 token 推送 LLM 生成的叙述性文本，
     * 最终以单个 "done" 事件推送结构化的完整复盘数据。
     * "done.data" 的格式与同步接口 {@code /api/ai/daily-review} 的响应格式一致。
     *
     * <p>事件类型说明：</p>
     * <ul>
     *   <li>{@code token} — LLM 逐字输出的叙述性文本片段</li>
     *   <li>{@code done}  — 流结束，data 包含完整结构化复盘数据</li>
     *   <li>{@code error} — 流异常，data 包含错误信息</li>
     * </ul>
     *
     * @param request  HTTP 请求（需要用户认证）
     * @param response HTTP 响应（设置 SSE 相关头信息）
     * @return 流式响应体
     *
     * <p>English: Streaming variant: narrative tokens are emitted as `token` events while the LLM
     * types them, and the final structured body lands as a single `done` event. Same
     * shape as {@code /api/ai/daily-review} response in `done.data`.</p>
     */
    @PostMapping(path = "/daily-review/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    StreamingResponseBody dailyReviewStream(HttpServletRequest request, HttpServletResponse response) {
        var user = current(request);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return out -> {
            try {
                var body = reviews.generateStream(user, chunk -> {
                    try {
                        var data = STREAM_MAPPER.writeValueAsString(chunk);
                        out.write(("event: token\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception ignored) { /* client gone */ }
                });
                var payload = STREAM_MAPPER.writeValueAsString(body);
                out.write(("event: done\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ex) {
                try {
                    var data = STREAM_MAPPER.writeValueAsString(ex.getMessage() == null ? "stream failed" : ex.getMessage());
                    out.write(("event: error\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) { /* nothing more we can do */ }
            }
        };
    }
}
