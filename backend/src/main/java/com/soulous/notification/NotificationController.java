package com.soulous.notification;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【通知 REST 控制器：提供通知相关的 HTTP API 接口，
 * 包括 SSE 实时流、分页查询、未读计数、标记已读等功能。
 * 认证由 JWT 过滤器统一处理，未认证请求不会到达此处。】
 */
@RestController
@RequestMapping("/api/notifications")
class NotificationController extends BaseController {
    /** 通知业务服务 */
    private final NotificationService notifications;
    /** SSE 实时推送服务 */
    private final NotificationSseService sse;

    /**
     * 【构造器：注入依赖】
     *
     * @param users         【用户服务，用于获取当前登录用户】
     * @param notifications 【通知业务服务】
     * @param sse           【SSE 推送服务】
     */
    NotificationController(UserService users, NotificationService notifications, NotificationSseService sse) {
        super(users);
        this.notifications = notifications;
        this.sse = sse;
    }

    /**
     * 【SSE 长轮询接口：用于推送通知的实时流。浏览器 EventSource 同源发送 Cookie，
     * 其中携带 access token。设置 X-Accel-Buffering: no 防止 nginx 等反向代理缓冲流数据。】
     *
     * <p>SSE long-poll for push notifications. Authentication is enforced by the JWT filter;
     * unauthenticated requests never reach here. Browser EventSource sends cookies same-origin,
     * which carries the access token. Sets X-Accel-Buffering: no so nginx/etc don't buffer
     * the stream into oblivion when sitting in front of the app.</p>
     *
     * @param request  【HTTP 请求，用于获取当前用户】
     * @param response 【HTTP 响应，用于设置缓存和代理相关头信息】
     * @return 【SSE 发射器，客户端通过此连接接收实时事件】
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(HttpServletRequest request, HttpServletResponse response) {
        var user = current(request);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return sse.subscribe(user.id);
    }

    /**
     * 【分页查询通知列表，支持筛选未读通知，返回包含分页信息和未读计数的完整响应】
     *
     * @param request    【HTTP 请求】
     * @param onlyUnread 【是否仅返回未读通知，默认 false】
     * @param page       【页码，默认 0】
     * @param size       【每页大小，默认 20】
     * @return 【包含 items、分页信息、unreadCount 的 Map】
     */
    @GetMapping
    Map<String, Object> list(HttpServletRequest request,
                              @RequestParam(value = "onlyUnread", defaultValue = "false") boolean onlyUnread,
                              @RequestParam(value = "page", defaultValue = "0") int page,
                              @RequestParam(value = "size", defaultValue = "20") int size) {
        var user = current(request);
        var p = notifications.list(user, onlyUnread, page, size);
        var body = new LinkedHashMap<String, Object>();
        body.put("items", NotificationService.view(p.getContent()));
        body.put("page", p.getNumber());
        body.put("size", p.getSize());
        body.put("totalElements", p.getTotalElements());
        body.put("totalPages", p.getTotalPages());
        body.put("unreadCount", notifications.unreadCount(user));
        return body;
    }

    /**
     * 【查询当前用户未读通知数量，前端轮询此接口用于更新角标】
     *
     * @param request 【HTTP 请求】
     * @return 【包含 unreadCount 的 Map】
     */
    @GetMapping("/unread-count")
    Map<String, Object> unreadCount(HttpServletRequest request) {
        return Map.of("unreadCount", notifications.unreadCount(current(request)));
    }

    /**
     * 【标记单条通知为已读】
     *
     * @param request 【HTTP 请求】
     * @param id      【通知 ID】
     * @return 【已读通知的 JSON 视图】
     */
    @PatchMapping("/{id}/read")
    Map<String, Object> markRead(HttpServletRequest request, @PathVariable Long id) {
        var n = notifications.markRead(current(request), id);
        return NotificationService.view(n);
    }

    /**
     * 【批量标记当前用户所有未读通知为已读】
     *
     * @param request 【HTTP 请求】
     * @return 【包含 ok 状态和标记数量的 Map】
     */
    @PostMapping("/read-all")
    Map<String, Object> markAllRead(HttpServletRequest request) {
        int n = notifications.markAllRead(current(request));
        return Map.of("ok", true, "marked", n);
    }
}
