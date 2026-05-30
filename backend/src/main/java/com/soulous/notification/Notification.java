package com.soulous.notification;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 【应用内通知实体。当用户关注的事件发生时（AI 审核完成、管理员处理申诉、内容被拦截等）
 * 由业务服务创建并持久化。UI 每 30 秒轮询 /api/notifications/unread-count 并渲染角标。
 *
 * refType + refId 将通知行链接到原始业务对象，前端可在用户点击时进行深度跳转
 * （如 AI_REVIEW_DONE → /submissions/{refId}）。】
 *
 * <p>In-app notification. Pushed by services when an event of user-interest happens
 * (AI review finished, admin reviewed an appeal, content was blocked, …). The
 * UI polls /api/notifications/unread-count every 30s and renders a bell badge.</p>
 *
 * <p>{@code refType + refId} link the row back to the originating artifact so the
 * frontend can deep-link on click (e.g. AI_REVIEW_DONE → /submissions/{refId}).</p>
 */
@Entity
@Table(
        name = "notification",
        indexes = {
                @Index(name = "idx_notification_user_read", columnList = "user_id,read_at"),
                @Index(name = "idx_notification_user_created", columnList = "user_id,created_at")
        }
)
public class Notification {
    /** 通知唯一标识，自增主键 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 通知所属用户，多对一关联，不可为空 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 通知类型，参见 NotificationType 常量定义，最大 32 字符 */
    @Column(nullable = false, length = 32)
    public String type; // see NotificationType for the canonical list

    /** 通知标题，最大 200 字符 */
    @Column(nullable = false, length = 200)
    public String title;

    /** 通知正文内容，最大 1000 字符 */
    @Column(length = 1000)
    public String body;

    /** 关联实体类型（如 SUBMISSION、APPEAL），用于前端深度跳转 */
    @Column(name = "ref_type", length = 32)
    public String refType;

    /** 关联实体 ID，配合 refType 使用 */
    @Column(name = "ref_id")
    public Long refId;

    /** 已读时间，NULL 表示未读 */
    @Column(name = "read_at")
    public LocalDateTime readAt;

    /** 创建时间，默认为当前时间 */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();
}
