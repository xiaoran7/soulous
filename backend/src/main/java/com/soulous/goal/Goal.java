package com.soulous.goal;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 【学习目标实体类：代表用户设定的学习方向或长期目标。
 *  一个目标关联多个学习任务和多个 AI 规划会话。
 *  目标有明确的状态生命周期，支持设定目标日期和 AI 蒸馏记忆。】
 */
@Entity
public class Goal {
    /** 【主键 ID，自增生成】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【关联的用户账户，多对一关系，不可为空】 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【目标标题，不能为空，最长200字符】 */
    @NotBlank
    @Column(nullable = false, length = 200)
    public String title;

    /** 【目标状态，默认为 ACTIVE（活跃），使用字符串存储枚举值】 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public GoalStatus status = GoalStatus.ACTIVE;

    /** 【目标完成日期，可为空表示无截止日期约束】 */
    public LocalDate targetDate;

    /** 【AI 蒸馏记忆：存储 AI 对话中提炼出的关键信息摘要，使用 TEXT 类型支持长文本。
     *  帮助 AI 在后续会话中快速回忆目标上下文，提升对话质量。】 */
    @Column(columnDefinition = "TEXT")
    public String distilledMemoryJson;

    /** 【关联的 AI 规划会话总数，每次开始新会话时递增】 */
    public int sessionCount = 0;

    /** 【目标创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
    /** 【最后更新时间，任何字段变更时刷新】 */
    public LocalDateTime updatedAt = LocalDateTime.now();
    /** 【最后一次 AI 规划会话的时间，用于排序和展示活跃度】 */
    public LocalDateTime lastSessionAt;
}
