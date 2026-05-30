package com.soulous.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 【通用安全/管理审计日志实体类，映射到 audit_log 表。
 * 与 admin_audit_log（审核队列日志）和 moderation_log（内容过滤日志）在设计上保持独立——
 * 后两者携带更丰富的领域特定列，各自独立存储。
 *
 * 操作者身份信息采用快照方式存储（用户名+角色），确保后续的用户删除或角色变更
 * 不会改写历史记录。actor_user_id 可为 null，因为某些事件（如未知用户名的登录失败）
 * 无法确定具体操作者。】
 *
 * <p>Generic security/admin audit row. Distinct from {@code admin_audit_log}
 * (review queue) and {@code moderation_log} (content filter) by design — those
 * two carry richer domain-specific columns and remain separate.</p>
 *
 * <p>Actor identity is snapshotted (username + role) so deletions or role flips
 * later don't rewrite history. {@code actor_user_id} is nullable because some
 * events (login failure for an unknown username) have no resolved actor.</p>
 */
@Entity
@Table(
        name = "audit_log",
        indexes = {
                @Index(name = "idx_audit_log_actor_created", columnList = "actor_user_id,created_at"),
                @Index(name = "idx_audit_log_action_created", columnList = "action,created_at"),
                @Index(name = "idx_audit_log_target", columnList = "target_type,target_id")
        }
)
public class AuditLog {
    /** 【审计日志唯一标识，自增主键】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【操作者用户ID，可为 null（如未知用户的登录失败事件）】 */
    @Column(name = "actor_user_id")
    public Long actorUserId;

    /** 【操作者用户名快照，最大长度 64】 */
    @Column(name = "actor_username", length = 64)
    public String actorUsername;

    /** 【操作者角色快照，最大长度 32】 */
    @Column(name = "actor_role", length = 32)
    public String actorRole;

    /** 【操作类型编码，如 LOGIN_SUCCESS、ADMIN_CREATE_USER 等，最大长度 64，不可为空】 */
    @Column(nullable = false, length = 64)
    public String action;

    /** 【操作目标类型，如 USER、SUBMISSION 等，最大长度 32】 */
    @Column(name = "target_type", length = 32)
    public String targetType;

    /** 【操作目标ID】 */
    @Column(name = "target_id")
    public Long targetId;

    /** 【客户端 IP 地址，最大长度 64】 */
    @Column(length = 64)
    public String ip;

    /** 【客户端 User-Agent 信息，最大长度 255】 */
    @Column(name = "user_agent", length = 255)
    public String userAgent;

    /** 【操作是否成功】 */
    @Column(nullable = false)
    public boolean success;

    /** 【操作详情描述，TEXT 类型支持长文本】 */
    @Column(columnDefinition = "TEXT")
    public String details;

    /** 【记录创建时间，默认为当前时间，不可为空】 */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();
}
