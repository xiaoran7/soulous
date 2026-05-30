package com.soulous.task;

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
 * 【学习任务实体：Soulous 系统的核心业务实体，代表用户创建的一个学习目标】
 *
 * 【设计思路：任务是用户学习路径的基本单元，包含任务元信息（标题、描述、类型、难度等）、
 * 状态流转（由 {@link TaskStatus} 管理）、时间线（创建→开始→提交→完成）、
 * 经验值体系（baseExp 为基础奖励）。一个用户可以创建多个任务，
 * 每个任务可有多次提交记录（{@link TaskSubmission}）。】
 */
@Entity
public class StudyTask {
    /** 【主键 ID，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    /** 【任务所属用户，不可为空，多对一关系】 */
    @ManyToOne(optional = false)
    public UserAccount user;
    /** 【任务标题，必填，最长 128 字符，用于在任务列表中展示】 */
    @NotBlank
    @Column(nullable = false, length = 128)
    public String title;
    /** 【任务描述，TEXT 类型，详细说明学习目标、要求和参考资料】 */
    @Column(columnDefinition = "TEXT")
    public String description;
    /** 【任务类型，默认 STUDY，枚举值包括 STUDY/PRACTICE/REVIEW 等，影响任务展示样式】 */
    @Enumerated(EnumType.STRING)
    public TaskType taskType = TaskType.STUDY;
    /** 【任务难度，默认 NORMAL，影响经验值倍率计算和任务排序】 */
    @Enumerated(EnumType.STRING)
    public Difficulty difficulty = Difficulty.NORMAL;
    /** 【课程名称，用于按课程维度对任务进行分组和筛选】 */
    public String courseName;
    /** 【预计学习时长（分钟），默认 30 分钟，用于任务规划和时间管理】 */
    public Integer estimatedMinutes = 30;
    /** 【实际学习时长（分钟），默认 0，由提交时的 studyMinutes 更新】 */
    public Integer actualMinutes = 0;
    /** 【基础经验值，默认 20，任务完成时获得的基础奖励，可被难度倍率调整】 */
    public Integer baseExp = 20;
    /** 【任务状态，默认 TODO，通过 {@link TaskStatus} 管理完整生命周期】 */
    @Enumerated(EnumType.STRING)
    public TaskStatus status = TaskStatus.TODO;
    /** 【截止日期，可选，超过后任务在列表中标记为逾期（前端样式变化）】 */
    public LocalDate deadline;
    /** 【任务创建时间，实体实例化时自动设置】 */
    public LocalDateTime createdAt = LocalDateTime.now();
    /** 【任务开始时间，用户点击"开始"时设置，用于计算任务持续时长】 */
    public LocalDateTime startedAt;
    /** 【任务提交时间，用户提交学习证明时设置】 */
    public LocalDateTime submittedAt;
    /** 【任务完成时间，AI 审核通过或管理员手动通过时设置】 */
    public LocalDateTime completedAt;

    /** 【关联的学习目标 ID，可选，用于将任务归入长期学习目标体系】 */
    @Column(name = "goal_id")
    public Long goalId;

    /** 【建议安排在周几（1=周一 … 7=周日），可选。
     *  由"AI 按课表排周计划"生成的任务携带此值，用于在任务列表/日视图按天展示；
     *  手动创建、与课表无关的任务为 null。】 */
    @Column(name = "scheduled_weekday")
    public Integer scheduledWeekday;
}
