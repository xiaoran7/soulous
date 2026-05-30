package com.soulous.aisession;

/**
 * 【会话类型枚举：区分 AI 规划会话的两种启动模式】
 *
 * <p>NEW_GOAL — 用户创建新目标时开启的初始规划会话；CHECK_IN — 用户对已有目标进行跟进打卡时开启的会话。</p>
 */
public enum SessionKind {
    /** 【新目标会话：用户首次提出学习目标时启动，AI 将引导拆解并生成任务计划】 */
    NEW_GOAL,
    /** 【打卡跟进会话：用户对已有目标进行阶段性回顾，AI 基于进展给出下一步建议】 */
    CHECK_IN
}
