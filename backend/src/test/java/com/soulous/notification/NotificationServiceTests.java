package com.soulous.notification;

import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.common.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end behavior of NotificationService: push lands a row, unread count
 * tracks accurately, mark-read/mark-all-read flip read state, and ownership
 * boundaries are enforced (user A can't mark user B's notifications).
 *
 * 【NotificationService 的端到端集成测试，覆盖以下核心场景：
 *  1. push() 写入通知行，unreadCount 正确追踪未读数
 *  2. markRead() 翻转已读状态，未读计数归零
 *  3. markAllRead() 批量清除未读积压
 *  4. 所有权边界：用户 A 不能标记用户 B 的通知（ForbiddenException）
 *  5. null 参数优雅处理（不抛 NPE）
 *  使用 H2 内存数据库。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class NotificationServiceTests {
    @Autowired UserService users;
    @Autowired NotificationService notifications;

    /**
     * 【测试推送通知后出现在列表中并计入未读数。
     *  验证：unreadCount=1，列表大小=1，type、refId 正确，readAt 为 null。】
     */
    @Test
    void pushAppearsInListAndCountsTowardUnread() {
        var user = registerFresh("push");
        notifications.push(user, NotificationType.AI_REVIEW_DONE,
                "AI 审核通过", "做得好", "SUBMISSION", 42L);

        assertThat(notifications.unreadCount(user)).isEqualTo(1);
        var page = notifications.list(user, false, 0, 20);
        assertThat(page.getContent()).hasSize(1);
        var n = page.getContent().get(0);
        assertThat(n.type).isEqualTo(NotificationType.AI_REVIEW_DONE);
        assertThat(n.refId).isEqualTo(42L);
        assertThat(n.readAt).isNull();
    }

    /**
     * 【测试标记已读后状态翻转：unreadCount 归零，
     *  未读列表为空，全部列表仍有 1 条记录。】
     */
    @Test
    void markReadFlipsState() {
        var user = registerFresh("read");
        notifications.push(user, NotificationType.APPEAL_REVIEWED, "申诉通过", null, "APPEAL", 7L);
        var n = notifications.list(user, false, 0, 20).getContent().get(0);

        notifications.markRead(user, n.id);

        assertThat(notifications.unreadCount(user)).isZero();
        assertThat(notifications.list(user, true, 0, 20).getContent()).isEmpty();
        assertThat(notifications.list(user, false, 0, 20).getContent()).hasSize(1);
    }

    /**
     * 【测试全部标记已读：3 条未读通知一次性清除，
     *  返回值为 3，unreadCount 归零。】
     */
    @Test
    void markAllReadClearsBacklog() {
        var user = registerFresh("readall");
        notifications.push(user, NotificationType.AI_REVIEW_DONE, "a", null, null, null);
        notifications.push(user, NotificationType.AI_REVIEW_DONE, "b", null, null, null);
        notifications.push(user, NotificationType.AI_REVIEW_DONE, "c", null, null, null);

        int n = notifications.markAllRead(user);

        assertThat(n).isEqualTo(3);
        assertThat(notifications.unreadCount(user)).isZero();
    }

    /**
     * 【测试标记他人通知已读被拒绝。
     *  Bob 试图标记 Alice 的通知应抛出 ForbiddenException。】
     */
    @Test
    void markingOtherUsersNotificationIsForbidden() {
        var alice = registerFresh("alice");
        var bob = registerFresh("bob");
        notifications.push(alice, NotificationType.AI_REVIEW_DONE, "alice's", null, null, null);
        var alicesId = notifications.list(alice, false, 0, 20).getContent().get(0).id;

        assertThatThrownBy(() -> notifications.markRead(bob, alicesId))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * 【测试 null 参数优雅处理：null user 和 null type 均不抛异常，
     *  不创建通知记录。】
     */
    @Test
    void pushSwallowsNullArgsGracefully() {
        var user = registerFresh("nulls");
        notifications.push(null, NotificationType.AI_REVIEW_DONE, "t", "b", null, null);
        notifications.push(user, null, "t", "b", null, null);
        assertThat(notifications.unreadCount(user)).isZero();
    }

    /**
     * 【辅助方法：创建唯一用户名的测试用户】
     */
    private UserAccount registerFresh(String prefix) {
        var unique = prefix + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", prefix, unique + "@example.com"));
        return users.byToken(auth.token());
    }
}
