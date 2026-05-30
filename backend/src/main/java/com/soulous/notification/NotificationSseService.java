package com.soulous.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 【通知 SSE 扇出服务：管理所有客户端的 Server-Sent Events 连接。
 *
 * 每个认证标签页打开 GET /api/notifications/stream 时获得独立的 SseEmitter；
 * 以 userId 为键存储，同一用户可打开多个标签页，每个都能收到推送。
 * Emitter 在完成/超时/IO 错误时自动清理。
 *
 * 25 秒心跳机制防止空闲代理（nginx 默认 60s、部分负载均衡器 30s）断开长连接。
 * SseEmitter.send() 在连接断开时抛异常，触发与显式断开相同的清理路径。
 *
 * SSE 是尽力而为的投递——用户离线时通过持久化的 Notification 行在下次轮询/登录时获取。
 * 绝不阻塞业务事务等待 SSE 投递完成。】
 *
 * <p>Server-Sent Events fan-out for notifications.</p>
 *
 * <p>Each authenticated tab that opens {@code GET /api/notifications/stream} gets its own
 * {@link SseEmitter}; we store them keyed by userId so one user can have multiple tabs and
 * each receives every push. Emitters self-prune on completion / timeout / IO error.</p>
 *
 * <p>The 25s heartbeat is there to keep idle proxies (nginx default 60s, some load balancers
 * 30s) from dropping the long-lived connection. {@link SseEmitter#send(Object)} on a dead
 * connection throws, which triggers the same cleanup path as an explicit disconnect.</p>
 *
 * <p>SSE is best-effort delivery — if a user is offline when a notification fires, they'll
 * still see it via the persisted {@link Notification} row and the next poll / login fetch.
 * We never block the originating business transaction on SSE delivery.</p>
 */
@Service
public class NotificationSseService {
    private static final Logger log = LoggerFactory.getLogger(NotificationSseService.class);

    /** 【Emitter 存活时间：30 分钟。足够长以避免空闲用户频繁重连，足够短以释放僵死连接的资源槽位。】
     * 30 min: long enough that idle users don't reconnect constantly; short enough that
     * a stuck connection eventually frees its slot. */
    static final long EMITTER_TTL_MS = 30L * 60 * 1000;

    /** 【按用户 ID 分组的 SSE 发射器映射表，支持同一用户多标签页并发连接】 */
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> byUser = new ConcurrentHashMap<>();

    /**
     * 【为用户注册新的 SSE Emitter。注册完成/超时/错误回调自动清理。
     * 发送 "ready" 事件让客户端确认流已建立，在首个真实通知到达前显示"在线"状态。】
     *
     * Register a new emitter for a user. The caller (controller) returns it from the handler.
     *
     * @param userId 【用户 ID】
     * @return 【新创建的 SseEmitter 实例】
     */
    public SseEmitter subscribe(Long userId) {
        if (userId == null) throw new IllegalArgumentException("userId required");
        var emitter = new SseEmitter(EMITTER_TTL_MS);
        var list = byUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        Runnable remove = () -> {
            var l = byUser.get(userId);
            if (l != null) {
                l.remove(emitter);
                if (l.isEmpty()) byUser.remove(userId, l);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ex -> remove.run());

        // A "hello" event lets the client confirm the stream is wired before the first
        // real notification arrives — useful for showing "live" state in the UI.
        // "hello" 事件让客户端在首个真实通知到达前确认流已连接——用于在 UI 中显示"在线"状态。
        try {
            emitter.send(SseEmitter.event().name("ready").data("{\"ok\":true}"));
        } catch (IOException ignored) {
            // Initial send can fail if the client disconnected during handshake — onError
            // / onCompletion would have run by then; the emitter is effectively dead.
            // 初始发送可能在握手期间失败（客户端已断开）——onError/onCompletion 已执行，Emitter 已失效。
        }
        return emitter;
    }

    /**
     * 【向指定用户的所有在线 Emitter 扇出事件。无订阅者时为空操作。】
     *
     * Fan out one event to every emitter for {@code userId}. No-op if none subscribed.
     *
     * @param userId    【目标用户 ID】
     * @param eventName 【事件名称】
     * @param payload   【事件数据载荷】
     */
    public void broadcast(Long userId, String eventName, Object payload) {
        if (userId == null) return;
        var list = byUser.get(userId);
        if (list == null || list.isEmpty()) return;
        for (var emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception ex) {
                // Dead connection — drop it. We don't propagate; the caller already
                // committed the DB row and shouldn't be punished for a stale socket.
                // 连接已死——丢弃。不向上抛出异常，调用方已提交 DB 行，不应因陈旧连接受罚。
                try { emitter.completeWithError(ex); } catch (Exception ignored) { /* */ }
            }
        }
    }

    /**
     * 【25 秒心跳定时任务。发送命名 "ping" 事件使代理感知到流量，
     * 客户端可据此显示"已连接"状态。失败的 Emitter 通过与 broadcast 相同的错误路径被清理。】
     *
     * 25s heartbeat. Sends a named "ping" event so proxies see traffic and clients can
     * surface a "still connected" indicator. Failing emitters are pruned via the same
     * error path used by broadcast.
     */
    @Scheduled(fixedDelay = 25_000L)
    void heartbeat() {
        if (byUser.isEmpty()) return;
        for (var entry : byUser.entrySet()) {
            for (var emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().name("ping").data(""));
                } catch (Exception ex) {
                    try { emitter.completeWithError(ex); } catch (Exception ignored) { /* */ }
                }
            }
        }
    }

    /** 【测试/可观测性辅助方法——查询指定用户的活跃 Emitter 数量。】
     *  Test/observability helper — number of active emitters for a user. */
    public int activeFor(Long userId) {
        if (userId == null) return 0;
        var list = byUser.get(userId);
        return list == null ? 0 : list.size();
    }

    /** 【测试/可观测性辅助方法——所有用户的活跃连接总数。】
     *  Test/observability helper — total active connections across all users. */
    public int activeTotal() {
        int total = 0;
        for (var l : byUser.values()) total += l.size();
        return total;
    }
}
