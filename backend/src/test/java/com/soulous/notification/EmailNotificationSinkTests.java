package com.soulous.notification;

import com.soulous.auth.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for the email sink. We exercise the dispatch path with a captured
 * {@link JavaMailSender} stub — full SMTP integration is out of scope; we only
 * care that the sink (a) formats the message correctly, (b) skips users with no
 * email, and (c) eats {@link MailException} without rethrowing.
 *
 * 【EmailNotificationSink 的单元测试。
 *  使用捕获型 JavaMailSender 桩验证邮件发送路径，不涉及真实 SMTP 集成。
 *  覆盖场景：
 *  1. 正确格式化邮件（发件人、收件人、主题前缀、正文包含标题和签名）
 *  2. 跳过无邮箱用户（null/空/空白邮箱）
 *  3. 吞噬 MailException 而非重新抛出（best-effort 合约）
 *  4. 忽略 null 通知或 null 用户的通知】
 */
class EmailNotificationSinkTests {

    /**
     * 【捕获型 JavaMailSender 实现：记录发送的 SimpleMailMessage，
     *  可配置下次发送时抛出 MailException 用于测试异常处理。】
     */
    static class CapturingSender implements JavaMailSender {
        final List<SimpleMailMessage> sent = new ArrayList<>();
        boolean failNext;  // 【为 true 时下次 send() 抛 MailException】

        @Override
        public void send(SimpleMailMessage simpleMessage) {
            if (failNext) throw new MailException("boom") {};
            sent.add(simpleMessage);
        }
        @Override public void send(SimpleMailMessage... simpleMessages) { for (var m : simpleMessages) send(m); }
        @Override public MimeMessage createMimeMessage() { throw new UnsupportedOperationException(); }
        @Override public MimeMessage createMimeMessage(InputStream contentStream) { throw new UnsupportedOperationException(); }
        @Override public void send(MimeMessage mimeMessage) { throw new UnsupportedOperationException(); }
        @Override public void send(MimeMessage... mimeMessages) { throw new UnsupportedOperationException(); }
        @Override public void send(org.springframework.mail.javamail.MimeMessagePreparator mimeMessagePreparator) { throw new UnsupportedOperationException(); }
        @Override public void send(org.springframework.mail.javamail.MimeMessagePreparator... mimeMessagePreparators) { throw new UnsupportedOperationException(); }
    }

    /**
     * 【辅助方法：创建带指定邮箱的测试用户】
     */
    private static UserAccount userWithEmail(String email) {
        var u = new UserAccount();
        u.id = 1L;
        u.username = "alice";
        u.email = email;
        return u;
    }

    /**
     * 【辅助方法：创建指定用户、标题、正文的通知对象】
     */
    private static Notification notice(UserAccount u, String title, String body) {
        var n = new Notification();
        n.id = 100L;
        n.user = u;
        n.type = "AI_REVIEW_DONE";
        n.title = title;
        n.body = body;
        return n;
    }

    /**
     * 【测试邮件发送格式：验证发件人为配置地址，收件人正确，
     *  主题包含配置前缀，正文包含标题、正文和 "— Soulous" 签名。】
     */
    @Test
    void sendsFormattedMessageToUserEmail() {
        var sender = new CapturingSender();
        var sink = new EmailNotificationSink(sender, "noreply@x.test", "[Test] ");

        sink.dispatch(notice(userWithEmail("alice@x.test"), "AI 审核通过", "做得不错"));

        assertThat(sender.sent).hasSize(1);
        var msg = sender.sent.get(0);
        assertThat(msg.getFrom()).isEqualTo("noreply@x.test");
        assertThat(msg.getTo()).containsExactly("alice@x.test");
        assertThat(msg.getSubject()).isEqualTo("[Test] AI 审核通过");
        assertThat(msg.getText()).contains("AI 审核通过").contains("做得不错").contains("— Soulous");
    }

    /**
     * 【测试跳过无邮箱用户：null、空字符串、纯空白邮箱均不发送邮件。】
     */
    @Test
    void skipsUserWithNoEmail() {
        var sender = new CapturingSender();
        var sink = new EmailNotificationSink(sender, "from@x.test", "[Test] ");

        sink.dispatch(notice(userWithEmail(null), "x", "y"));
        sink.dispatch(notice(userWithEmail(""), "x", "y"));
        sink.dispatch(notice(userWithEmail("   "), "x", "y"));

        assertThat(sender.sent).isEmpty();
    }

    /**
     * 【测试吞噬 MailException：发送失败时不重新抛出异常（best-effort 合约），
     *  验证不发送任何邮件且不抛异常。】
     */
    @Test
    void swallowsMailExceptionInsteadOfRethrowing() {
        var sender = new CapturingSender();
        sender.failNext = true;
        var sink = new EmailNotificationSink(sender, "from@x.test", "[Test] ");

        // Must not throw — the NotificationSink contract is best-effort.
        // 【不抛异常 — NotificationSink 合约是尽力而为】
        sink.dispatch(notice(userWithEmail("alice@x.test"), "x", "y"));
        assertThat(sender.sent).isEmpty();
    }

    /**
     * 【测试忽略 null 通知和 null 用户通知：两种情况均不发送邮件，不抛 NPE。】
     */
    @Test
    void ignoresNullNotificationOrNullUser() {
        var sender = new CapturingSender();
        var sink = new EmailNotificationSink(sender, "from@x.test", "[Test] ");

        sink.dispatch(null);
        var n = new Notification();
        n.user = null;
        sink.dispatch(n);
        assertThat(sender.sent).isEmpty();
    }
}
