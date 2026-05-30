package com.soulous.focus;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;

/**
 * 【专注会话实体类：代表用户的一次专注计时会话。
 *  采用计时器模式而非倒计时模式，支持暂停和恢复。
 *  已用时间通过 lastStartedAt 和 elapsedSeconds 两个字段配合计算，
 *  暂停时冻结计时，恢复时重新启动。】
 */
@Entity
public class FocusSession {
    /** 【主键 ID，自增生成】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    /** 【关联的用户账户，多对一关系，不可为空】 */
    @ManyToOne(optional = false)
    public UserAccount user;
    /** 【专注会话标题，描述本次专注的内容，最长128字符】 */
    @Column(nullable = false, length = 128)
    public String title;
    /** 【计划专注时长（分钟），默认25分钟（一个番茄钟）】 */
    public Integer plannedMinutes = 25;
    /** 【累计已用时间（秒），暂停时不增长，通过 lastStartedAt 动态计算当前段】 */
    public Integer elapsedSeconds = 0;
    /** 【可选的关联学习任务 ID，不使用外键约束而是逻辑关联，便于灵活管理】 */
    @Column(name = "task_id")
    public Long taskId;
    /** 【专注状态，默认为 RUNNING（运行中）】 */
    @Enumerated(EnumType.STRING)
    public FocusStatus status = FocusStatus.RUNNING;
    /** 【会话开始时间，记录首次启动时刻】 */
    public LocalDateTime startedAt = LocalDateTime.now();
    /** 【最近一次启动时间，用于计算当前运行段的已用秒数，暂停时置为 null】 */
    public LocalDateTime lastStartedAt = LocalDateTime.now();
    /** 【会话结束时间，完成或中止时设置】 */
    public LocalDateTime endedAt;
    /** 【记录创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
