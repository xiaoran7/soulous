package com.soulous.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 【邮件通知投递器：当配置项 soulous.notification.email.enabled=true 时激活，通过 Spring Mail 发送邮件通知。
 * 采用条件装配策略，未配置该属性时 Bean 不会创建，也无需配置 spring.mail.* 相关参数。
 * JavaMail 仅在设置了邮件主机时才会自动配置 JavaMailSender，因此配置错误会在启动时立即暴露，
 * 而非静默丢弃邮件。
 *
 * 每个用户级别的控制：仅当用户已绑定邮箱时才发送邮件。
 * 当前未实现按通知类型的退订机制，所有类型的通知都会发送。
 * 后续计划引入 user_notification_pref 表，以 (userId, type) 为键进行偏好管理。】
 *
 * <p>Sends notifications by email when {@code soulous.notification.email.enabled=true}.</p>
 *
 * <p>Activation is gated by config so the dependency is opt-in — without the property set,
 * this bean isn't created and {@code spring.mail.*} doesn't need to be configured. JavaMail
 * itself only auto-configures a {@link JavaMailSender} when a host is set, so a misconfigured
 * deploy fails at startup with a clear error rather than silently dropping mail.</p>
 *
 * <p>Per-user opt-in: we only send when the recipient actually has an email on file. We do
 * not currently store a per-type opt-out — every notification type goes out. Future work:
 * a {@code user_notification_pref} table keyed by (userId, type).</p>
 */
@Component
@ConditionalOnProperty(prefix = "soulous.notification.email", name = "enabled", havingValue = "true")
public class EmailNotificationSink implements NotificationSink {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationSink.class);

    /** Spring 邮件发送器，由框架自动装配 */
    private final JavaMailSender mail;
    /** 发件人地址，默认 no-reply@soulous.local，可通过配置覆盖 */
    private final String fromAddress;
    /** 邮件主题前缀，默认 "[Soulous] "，用于品牌标识 */
    private final String subjectPrefix;

    /**
     * 【构造器：注入邮件发送器及邮件相关配置参数】
     *
     * @param mail          【Spring 自动装配的邮件发送器】
     * @param fromAddress   【发件人地址，支持通过 soulous.notification.email.from 配置】
     * @param subjectPrefix 【邮件主题前缀，支持通过 soulous.notification.email.subject-prefix 配置】
     */
    public EmailNotificationSink(JavaMailSender mail,
                                 @Value("${soulous.notification.email.from:no-reply@soulous.local}") String fromAddress,
                                 @Value("${soulous.notification.email.subject-prefix:[Soulous] }") String subjectPrefix) {
        this.mail = mail;
        this.fromAddress = fromAddress;
        this.subjectPrefix = subjectPrefix;
    }

    /**
     * 【发送邮件通知。遵循 NotificationSink 契约：内部捕获所有异常，不会向上抛出，
     * 确保邮件发送失败不会影响通知管道的正常运行（DB 记录 + SSE 通道已先行投递）。】
     *
     * @param notification 【待发送的通知对象】
     */
    @Override
    public void dispatch(Notification notification) {
        if (notification == null || notification.user == null) return;
        var to = notification.user.email;
        if (to == null || to.isBlank()) return; // user hasn't set an email — nothing to send

        var msg = new SimpleMailMessage();
        msg.setFrom(fromAddress);
        msg.setTo(to);
        msg.setSubject(subjectPrefix + (notification.title == null ? "" : notification.title));
        msg.setText(buildBody(notification));
        try {
            mail.send(msg);
        } catch (MailException ex) {
            // Swallow per NotificationSink contract — a busted SMTP must not break the
            // notification pipeline. The DB row + SSE channel already delivered the event.
            log.warn("email dispatch failed (id={} to={}): {}", notification.id, to, ex.getMessage());
        }
    }

    /**
     * 【构建邮件正文内容，包含标题、正文和署名】
     *
     * @param n 【通知对象】
     * @return 【拼装后的邮件正文字符串】
     */
    private String buildBody(Notification n) {
        var sb = new StringBuilder();
        if (n.title != null) sb.append(n.title).append("\n\n");
        if (n.body != null && !n.body.isBlank()) sb.append(n.body).append("\n\n");
        sb.append("— Soulous");
        return sb.toString();
    }
}
