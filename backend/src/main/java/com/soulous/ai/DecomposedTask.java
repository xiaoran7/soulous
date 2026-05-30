package com.soulous.ai;

import com.soulous.task.Difficulty;
import com.soulous.task.TaskType;

/**
 * 【AI 任务分解的单个子任务数据模型。
 *
 * <p>当用户提交一个高阶目标（如"学习 Java 并发编程"）时，AI 会将其拆解为
 * 多个具体的、可执行的子任务。每个子任务包含标题、描述、类型、难度、
 * 预估时长和基础经验值等元信息。</p>
 *
 * <p>使用 Java record 定义，天然不可变且自带 equals/hashCode/toString。
 * 与 {@link DecomposeResponse} 配合，组成 AI 分解接口的完整响应结构。</p>
 */
public record DecomposedTask(
    /** 【子任务的简短标题，用于前端展示和任务列表】 */
    String title,

    /** 【子任务的详细描述，说明具体要完成什么内容】 */
    String description,

    /** 【任务类型枚举，取值参见 {@link TaskType}（如编码、阅读、练习等）】 */
    TaskType taskType,

    /** 【难度等级枚举，取值参见 {@link Difficulty}（如 EASY、MEDIUM、HARD）】 */
    Difficulty difficulty,

    /** 【预估完成时间（分钟），用于前端展示和经验值计算的参考】 */
    Integer estimatedMinutes,

    /** 【完成该子任务获得的基础经验值，用于成长系统】 */
    Integer baseExp
) {}
