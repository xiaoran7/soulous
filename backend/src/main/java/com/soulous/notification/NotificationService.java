package com.soulous.notification;

import com.soulous.auth.UserAccount;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.NotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【通知服务：负责通知的创建、投递、查询和已读标记等核心业务逻辑。
 * 集成持久化层（JPA）、实时推送层（SSE）和异步投递通道（Sink），
 * 采用三层独立容错机制，确保单个通道的故障不会影响其他通道。】
 */
@Service
public class NotificationService {
    /** 通知数据访问仓库 */
    private final NotificationRepository repo;
    /** Micrometer 指标注册器，用于通知推送计数等监控 */
    private final MeterRegistry meterRegistry;
    /** SSE 实时推送服务，用于向在线客户端广播通知 */
    private final NotificationSseService sse;
    /** 异步通知投递通道列表（如邮件、Webhook 等），可为空 */
    private final java.util.List<NotificationSink> sinks;

    /**
     * 【构造器：注入所有依赖组件】
     *
     * @param repo          【通知数据仓库】
     * @param meterRegistry 【指标监控注册器】
     * @param sse           【SSE 实时推送服务】
     * @param sinks         【异步投递通道列表，可能为 null，构造时做空安全处理】
     */
    NotificationService(NotificationRepository repo, MeterRegistry meterRegistry,
                        NotificationSseService sse, java.util.List<NotificationSink> sinks) {
        this.repo = repo;
        this.meterRegistry = meterRegistry;
        this.sse = sse;
        this.sinks = sinks == null ? java.util.List.of() : sinks;
    }

    /**
     * 【推送通知：设计为 fire-and-forget 模式，任何失败都被吞掉，
     * 确保通知问题永远不会破坏触发它的业务操作（如 AI 审核通知失败 ≠ AI 审核失败）。
     *
     * 投递顺序：① DB 持久化（确保不丢） → ② SSE 广播（实时在线客户端） → ③ 异步通道（邮件等）。
     * 每个步骤独立 try/catch，一个通道的失败不影响其他通道。】
     *
     * Publish a notification. Designed so callers can fire-and-forget — any failure
     * is swallowed so a notification problem can never break the business operation
     * that triggered it (failed AI review notification != failed AI review).
     *
     * <p>Delivery order: DB row first (durable record), then SSE broadcast for any
     * online tab, then out-of-band sinks (email etc.). Each step is independently
     * try/caught so one channel's failure doesn't break the others.</p>
     *
     * @param user    【通知所属用户】
     * @param type    【通知类型，参见 NotificationType 常量】
     * @param title   【通知标题】
     * @param body    【通知正文】
     * @param refType 【关联实体类型，如 SUBMISSION、APPEAL】
     * @param refId   【关联实体 ID】
     */
    @Transactional
    public void push(UserAccount user, String type, String title, String body,
                     String refType, Long refId) {
        if (user == null || type == null) return;
        Notification saved = null;
        try {
            var n = new Notification();
            n.user = user;
            n.type = type;
            n.title = truncate(title, 200);
            n.body = truncate(body, 1000);
            n.refType = refType;
            n.refId = refId;
            saved = repo.save(n);
            var typeTag = type == null ? "unknown" : type;
            meterRegistry.counter("soulous.notification.pushed.total", "type", typeTag).increment();
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(NotificationService.class)
                    .warn("notification push failed (type={} user={})", type, user.id, ex);
            return; // no DB row → nothing to broadcast or email
            // 没有 DB 记录则无需广播或发送邮件
        }

        // SSE: best-effort live push to any open tab.
        // SSE：尽力而为的实时推送到所有已打开的标签页。
        try {
            if (sse != null) sse.broadcast(user.id, "notification", view(saved));
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(NotificationService.class)
                    .warn("SSE broadcast failed (id={} user={})", saved.id, user.id, ex);
        }

        // Out-of-band sinks (email etc.) — each isolated; one failing doesn't skip the rest.
        // 异步通道（邮件等）—— 各自隔离，一个失败不会跳过其余通道。
        for (var sink : sinks) {
            try {
                sink.dispatch(saved);
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(NotificationService.class)
                        .warn("sink {} dispatch failed (id={} user={})", sink.getClass().getSimpleName(), saved.id, user.id, ex);
            }
        }
    }

    /**
     * 【分页查询用户通知列表】
     *
     * @param user       【当前用户】
     * @param onlyUnread 【是否仅查询未读通知】
     * @param page       【页码，从 0 开始】
     * @param size       【每页大小，最大 100】
     * @return 【分页结果】
     */
    public Page<Notification> list(UserAccount user, boolean onlyUnread, int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        return onlyUnread
                ? repo.findByUserAndReadAtIsNullOrderByCreatedAtDesc(user, pageable)
                : repo.findByUserOrderByCreatedAtDesc(user, pageable);
    }

    /**
     * 【查询用户未读通知数量，用于前端角标展示】
     *
     * @param user 【当前用户】
     * @return 【未读通知数量】
     */
    public long unreadCount(UserAccount user) {
        return repo.countByUserAndReadAtIsNull(user);
    }

    /**
     * 【标记单条通知为已读。包含权限校验：通知不存在则抛 NotFoundException，非本人通知则抛 ForbiddenException。】
     *
     * @param user 【当前用户】
     * @param id   【通知 ID】
     * @return 【已标记为已读的通知对象】
     * @throws NotFoundException  【通知不存在时抛出】
     * @throws ForbiddenException 【操作他人通知时抛出】
     */
    @Transactional
    public Notification markRead(UserAccount user, Long id) {
        var n = repo.findById(id).orElseThrow(() -> new NotFoundException("通知不存在"));
        if (!n.user.id.equals(user.id)) throw new ForbiddenException("不能操作他人的通知");
        if (n.readAt == null) {
            n.readAt = LocalDateTime.now();
            repo.save(n);
        }
        return n;
    }

    /**
     * 【批量标记用户所有未读通知为已读，通过自定义 JPQL 批量更新提高效率】
     *
     * @param user 【当前用户】
     * @return 【被标记为已读的通知数量】
     */
    @Transactional
    public int markAllRead(UserAccount user) {
        return repo.markAllReadForUser(user, LocalDateTime.now());
    }

    /** 【JSON 友好的通知视图投影，用于 API 响应，避免直接暴露 JPA 实体。】
     * JSON-friendly projection for the API. */
    public static Map<String, Object> view(Notification n) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", n.id);
        m.put("type", n.type);
        m.put("title", n.title);
        m.put("body", n.body == null ? "" : n.body);
        m.put("refType", n.refType);
        m.put("refId", n.refId);
        m.put("readAt", n.readAt);
        m.put("createdAt", n.createdAt);
        return m;
    }

    /**
     * 【批量将通知列表转换为 JSON 视图】
     *
     * @param items 【通知列表】
     * @return 【JSON 视图列表】
     */
    public static List<Map<String, Object>> view(List<Notification> items) {
        return items.stream().map(NotificationService::view).toList();
    }

    /**
     * 【字符串截断工具方法，防止超长内容写入数据库】
     *
     * @param s   【原始字符串】
     * @param max 【最大长度】
     * @return 【截断后的字符串，若原始为 null 则返回 null】
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
