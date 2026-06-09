package com.soulous.reminder;

import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.checkin.CheckinService;
import com.soulous.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【ReminderService 端到端：未打卡且有邮箱的用户收到提醒通知；已打卡的不提醒。
 *  使用 H2 内存库。真实发邮件由 EmailNotificationSink 负责（默认关，不在此测）。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reminder-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class ReminderServiceTests {
    @Autowired UserService users;
    @Autowired ReminderService reminders;
    @Autowired NotificationService notifications;
    @Autowired CheckinService checkin;

    @Test
    void remindsUserWhoHasNotCheckedIn() {
        var user = fresh("remind");
        long before = notifications.unreadCount(user);

        reminders.remindNotCheckedIn(LocalDate.now());

        assertThat(notifications.unreadCount(user)).isEqualTo(before + 1);
        assertThat(notifications.list(user, false, 0, 10).getContent())
                .anySatisfy(n -> assertThat(n.type).isEqualTo("DAILY_REMINDER"));
    }

    @Test
    void doesNotRemindUserWhoCheckedIn() {
        var user = fresh("checkedin");
        checkin.checkin(user); // 今天已打卡
        long before = notifications.unreadCount(user);

        reminders.remindNotCheckedIn(LocalDate.now());

        assertThat(notifications.unreadCount(user)).isEqualTo(before); // 不新增提醒
    }

    private UserAccount fresh(String prefix) {
        var unique = prefix + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", prefix, unique + "@example.com"));
        return users.byToken(auth.token());
    }
}
