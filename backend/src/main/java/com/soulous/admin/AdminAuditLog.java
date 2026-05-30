package com.soulous.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

/**
 * 【管理员审核日志实体类，映射到 admin_audit_log 表。
 * 记录管理员的所有审核操作（批准、驳回、要求补充、申诉审核等），
 * 包括操作者信息、目标实体、经验值变动、结果状态和审核意见。
 * 与通用审计日志（AuditLog）不同，本实体专注于审核业务场景，
 * 携带更丰富的领域特定字段。】
 */
@Entity
public class AdminAuditLog {
    /** 【审核日志唯一标识，自增主键】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【执行审核操作的管理员ID】 */
    public Long adminId;

    /** 【管理员用户名，最大长度 64】 */
    @Column(length = 64)
    public String adminUsername;

    /** 【审核操作类型枚举（APPROVE/REJECT/NEED_MORE/APPEAL_REVIEW），不可为空】 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public AdminAuditAction action;

    /** 【审核目标类型枚举（SUBMISSION/APPEAL），不可为空】 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public AuditTargetType targetType;

    /** 【审核目标记录ID】 */
    public Long targetId;

    /** 【经验值变动数量，批准时为正数，驳回/需补充时为 null】 */
    public Integer expAmount;

    /** 【操作结果状态，如 MANUAL_APPROVED、MANUAL_REJECTED 等，最大长度 64】 */
    @Column(length = 64)
    public String resultStatus;

    /** 【管理员审核意见/评论，TEXT 类型支持长文本】 */
    @Column(columnDefinition = "TEXT")
    public String comment;

    /** 【审核日志创建时间，默认为当前时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
