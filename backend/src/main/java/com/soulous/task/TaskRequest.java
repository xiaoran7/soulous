package com.soulous.task;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * 【任务创建/更新请求 DTO：使用 Java Record 实现不可变数据传输对象】
 *
 * 【设计说明：作为 TaskController 的请求体，用于创建和更新学习任务。
 * 使用 @NotBlank 校验标题必填，其余字段为可选，支持部分更新。
 * 与 {@link TaskService#apply} 方法配合，将请求数据映射到 {@link StudyTask} 实体。】
 */
public record TaskRequest(
        /** 【任务标题，必填，最长 128 字符（由实体层约束）】 */
        @NotBlank String title,
        /** 【任务描述，可选，详细说明学习目标和要求】 */
        String description,
        /** 【任务类型，可选，如 STUDY/PRACTICE/REVIEW 等，不传则使用默认值】 */
        TaskType taskType,
        /** 【任务难度，可选，影响经验值计算，不传则使用默认值 NORMAL】 */
        Difficulty difficulty,
        /** 【课程名称，可选，用于按课程分类任务】 */
        String courseName,
        /** 【大分类，可选，更高一层的主题分组，与 AI 拆解的对话分类共用命名】 */
        String category,
        /** 【预计学习时长（分钟），可选，用于任务规划和时间估算】 */
        Integer estimatedMinutes,
        /** 【基础经验值，可选，任务完成时获得的基础奖励】 */
        Integer baseExp,
        /** 【截止日期，可选，超过截止日期后任务标记为逾期】 */
        LocalDate deadline,
        /** 【建议安排在周几（1-7，周一=1），可选。由 AI 按课表排课的任务携带】 */
        Integer scheduledWeekday
) {}
