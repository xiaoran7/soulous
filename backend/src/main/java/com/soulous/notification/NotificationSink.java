package com.soulous.notification;

/**
 * 【通知异步投递通道接口。实现类作为 Spring Bean 自动注册，
 * NotificationService.push() 在 DB 行提交后向所有 Sink 扇出投递。
 * 实现类必须吞掉所有异常——绝不能让投递问题泄漏到业务事务中。
 *
 * 内置实现：
 * - EmailNotificationSink：当 soulous.notification.email.enabled=true 且用户有邮箱时通过邮件发送。
 *
 * SSE 故意不作为 Sink 实现：SSE 是面向已在线客户端的进程内推送；
 * Sink 用于带外通道（邮件、未来可能的 Webhook、移动端推送）。】
 *
 * <p>Side-channel for outbound notification delivery. Implementations are auto-discovered as
 * Spring beans; {@link NotificationService#push} fans out to every sink after the DB row
 * is committed. Failures must be swallowed by the sink — never let a delivery problem
 * leak back into the business transaction.</p>
 *
 * <p>Built-in sinks:</p>
 * <ul>
 *   <li>{@link EmailNotificationSink} — sends via {@code spring-boot-starter-mail} when
 *       {@code soulous.notification.email.enabled=true} AND the user has an email.</li>
 * </ul>
 *
 * <p>SSE is intentionally <em>not</em> a sink: SSE is push-to-already-online-client and runs
 * in-process; sinks are for out-of-band channels (email, future webhooks, mobile push).</p>
 */
public interface NotificationSink {
    /** 【尽力而为的投递，不得抛出异常——记录日志后吞掉。】
     *  Best-effort dispatch. Must not throw — log and swallow. */
    void dispatch(Notification notification);
}
