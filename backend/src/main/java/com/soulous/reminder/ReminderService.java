package com.soulous.reminder;

import com.soulous.auth.UserRepository;
import com.soulous.auth.UserRole;
import com.soulous.checkin.CheckinRepository;
import com.soulous.notification.NotificationService;
import com.soulous.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 【每日提醒服务：傍晚定时扫描「今天还没打卡」且填了邮箱的普通用户，推送一条「别忘了打卡」通知。
 *  通知经由通知管道投递——若开启了邮件 sink（soulous.notification.email.enabled=true 且配好 SMTP），
 *  就会真正发邮件；否则仅落库（开发期不发真实邮件）。管理员不发学习提醒。】
 */
@Service
public class ReminderService {
    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final UserRepository users;
    private final CheckinRepository checkins;
    private final NotificationService notifications;
    /** 【是否启用每日提醒】 */
    private final boolean enabled;

    public ReminderService(UserRepository users, CheckinRepository checkins,
                           NotificationService notifications,
                           @Value("${soulous.reminder.enabled:true}") boolean enabled) {
        this.users = users;
        this.checkins = checkins;
        this.notifications = notifications;
        this.enabled = enabled;
    }

    /** 【每日定时入口：默认每天 20:00 跑一次未打卡提醒】 */
    @Scheduled(cron = "${soulous.reminder.cron:0 0 20 * * *}")
    public void sendDailyReminders() {
        if (!enabled) return;
        int sent = remindNotCheckedIn(LocalDate.now());
        if (sent > 0) log.info("Daily check-in reminders pushed to {} user(s)", sent);
    }

    /**
     * 【给「今天未打卡 + 有邮箱 + 非管理员」的用户推送提醒，返回发送数量。
     *  独立于定时入口，便于测试直接调用。】
     *
     * @param today 【基准日期】
     */
    @Transactional
    public int remindNotCheckedIn(LocalDate today) {
        int sent = 0;
        for (var user : users.findAll()) {
            if (user.role == UserRole.ADMIN) continue;
            if (user.email == null || user.email.isBlank()) continue;
            if (checkins.findByUserAndCheckinDate(user, today).isPresent()) continue;
            notifications.push(user, NotificationType.DAILY_REMINDER,
                    "别忘了今天的打卡",
                    "今天还没打卡哦，回来签到领金币、陪宠物一起成长吧～",
                    "CHECKIN", null);
            sent++;
        }
        return sent;
    }
}
