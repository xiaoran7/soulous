package com.soulous.rag;

/**
 * 【嵌入向量来源类型枚举】
 * 定义存储的嵌入向量所代表的工件类型。该枚举驱动两个核心行为：
 * 1. 当源数据发生变化时，决定如何重新索引；
 * 2. 在检索命中时，决定如何为 LLM 提示词标注来源标签。
 *
 * <p>English: What kind of artifact a stored embedding represents. Drives both how we re-index when the
 * source changes and how we label retrieved hits for the LLM prompt.</p>
 */
public enum EmbeddingSourceType {
    /** 【目标蒸馏记忆】对应 Goal.distilledMemoryJson，即用户目标经过 AI 蒸馏后的长期记忆摘要。 */
    /** Distilled long-term memory of a goal (Goal.distilledMemoryJson). */
    GOAL_MEMORY,

    /** 【会话滚动摘要】对应 PlanningSession.runningSummary，即每轮规划会话中前几轮对话的滚动摘要。 */
    /** Per-session rolling summary of early turns (PlanningSession.runningSummary). */
    SESSION_SUMMARY,

    /** 【已完成任务】已完成任务的标题+描述，用于检索用户过去的学习成果。 */
    /** Title+description of a completed task. */
    COMPLETED_TASK,

    /** 【每日复盘摘要】聚合的每日复盘片段，每个用户每天一行，用于跨天上下文检索。 */
    /** Aggregated daily-review snippet (one row per user per day). */
    DAILY_REVIEW
}
