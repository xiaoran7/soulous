package com.soulous.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulous.auth.UserService;
import com.soulous.common.ratelimit.RateLimit;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 【AI 拆解对话 REST 控制器（Gemini 式重构版）：/api/chat】
 *
 * <p>提供分类 CRUD、对话 CRUD（创建/获取/重命名/移动/删除）、发送消息（普通 + SSE 流式）、
 * 计划草案编辑/删除/确认。涉及 LLM 调用的端点配置小时级 + 天级双重限流。</p>
 */
@RestController
@RequestMapping("/api/chat")
class ChatController extends BaseController {
    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper();

    private final ChatService service;

    ChatController(UserService users, ChatService service) {
        super(users);
        this.service = service;
    }

    // ----- categories -----

    @GetMapping("/tree")
    ChatDtos.TreeView tree(HttpServletRequest request) {
        return service.tree(current(request));
    }

    @PostMapping("/categories")
    ChatDtos.CategoryView createCategory(HttpServletRequest request,
                                         @Valid @RequestBody ChatDtos.CategoryRequest body) {
        return service.createCategory(current(request), body.name());
    }

    @PatchMapping("/categories/{id}")
    ChatDtos.CategoryView renameCategory(HttpServletRequest request, @PathVariable Long id,
                                         @Valid @RequestBody ChatDtos.CategoryRequest body) {
        return service.renameCategory(current(request), id, body.name());
    }

    @DeleteMapping("/categories/{id}")
    ChatDtos.DeleteResult deleteCategory(HttpServletRequest request, @PathVariable Long id) {
        return service.deleteCategory(current(request), id);
    }

    // ----- conversations -----

    @PostMapping("/conversations")
    ChatDtos.ConversationView createConversation(HttpServletRequest request,
                                                 @RequestBody(required = false) ChatDtos.CreateConversationRequest body) {
        var categoryId = body == null ? null : body.categoryId();
        return service.createConversation(current(request), categoryId);
    }

    @GetMapping("/conversations/{id}")
    ChatDtos.ConversationView getConversation(HttpServletRequest request, @PathVariable Long id) {
        return service.getConversation(current(request), id);
    }

    @PatchMapping("/conversations/{id}")
    ChatDtos.ConversationView updateConversation(HttpServletRequest request, @PathVariable Long id,
                                                 @RequestBody ChatDtos.UpdateConversationRequest body) {
        return service.updateConversation(current(request), id, body);
    }

    @DeleteMapping("/conversations/{id}")
    ChatDtos.DeleteResult deleteConversation(HttpServletRequest request, @PathVariable Long id) {
        return service.deleteConversation(current(request), id);
    }

    // ----- messaging -----

    @PostMapping("/conversations/{id}/messages")
    @RateLimit(name = "ai-hourly", capacity = 60, refillTokens = 60, refillPeriod = 1,
            refillUnit = TimeUnit.HOURS, key = RateLimit.KeyType.USER)
    @RateLimit(name = "ai-daily", capacity = 200, refillTokens = 200, refillPeriod = 1,
            refillUnit = TimeUnit.DAYS, key = RateLimit.KeyType.USER)
    ChatDtos.ConversationView postMessage(HttpServletRequest request, @PathVariable Long id,
                                          @Valid @RequestBody ChatDtos.MessageRequest body) {
        return service.postMessage(current(request), id, body.content());
    }

    @PostMapping(path = "/conversations/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(name = "ai-hourly", capacity = 60, refillTokens = 60, refillPeriod = 1,
            refillUnit = TimeUnit.HOURS, key = RateLimit.KeyType.USER)
    @RateLimit(name = "ai-daily", capacity = 200, refillTokens = 200, refillPeriod = 1,
            refillUnit = TimeUnit.DAYS, key = RateLimit.KeyType.USER)
    StreamingResponseBody postMessageStream(HttpServletRequest request, HttpServletResponse response,
                                            @PathVariable Long id,
                                            @Valid @RequestBody ChatDtos.MessageRequest body) {
        var user = current(request);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        var content = body.content();
        return out -> {
            try {
                var view = service.postMessageStream(user, id, content, chunk -> {
                    try {
                        var data = STREAM_MAPPER.writeValueAsString(chunk);
                        out.write(("event: token\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception ignored) { /* client disconnected */ }
                }, status -> {
                    // agent 工具调用状态 {stage, name}：前端据此显示「正在调用工具…」指示
                    try {
                        out.write(("event: status\ndata: " + STREAM_MAPPER.writeValueAsString(status) + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception ignored) { /* client disconnected */ }
                });
                var payload = STREAM_MAPPER.writeValueAsString(view);
                out.write(("event: done\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ex) {
                try {
                    var data = STREAM_MAPPER.writeValueAsString(ex.getMessage() == null ? "stream failed" : ex.getMessage());
                    out.write(("event: error\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) { /* nothing we can do */ }
            }
        };
    }

    // ----- plan editing / commit -----

    @PatchMapping("/conversations/{id}/plan/tasks/{index}")
    ChatDtos.ConversationView editPlanTask(HttpServletRequest request, @PathVariable Long id,
                                           @PathVariable int index,
                                           @RequestBody ChatDtos.EditPlanTaskRequest body) {
        return service.editPlanTask(current(request), id, index, body);
    }

    @DeleteMapping("/conversations/{id}/plan/tasks/{index}")
    ChatDtos.ConversationView deletePlanTask(HttpServletRequest request, @PathVariable Long id,
                                             @PathVariable int index) {
        return service.deletePlanTask(current(request), id, index);
    }

    /** 【弃用整份计划草案：对所有任务都不满意时的一键出口】 */
    @DeleteMapping("/conversations/{id}/plan")
    ChatDtos.ConversationView dismissPlan(HttpServletRequest request, @PathVariable Long id) {
        return service.dismissPlan(current(request), id);
    }

    @PostMapping("/conversations/{id}/commit")
    ChatDtos.ConversationView commit(HttpServletRequest request, @PathVariable Long id) {
        return service.commitPlan(current(request), id);
    }
}
