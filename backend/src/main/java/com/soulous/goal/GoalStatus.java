package com.soulous.goal;

/**
 * 【目标状态枚举：描述学习目标的生命周期状态。
 *  状态流转：ACTIVE → PAUSED（暂停）→ ACTIVE（恢复）
 *           ACTIVE → ACHIEVED（达成）
 *           ACTIVE → ABANDONED（放弃）
 *           任意状态 → ARCHIVED（归档）】
 */
public enum GoalStatus {
    /** 【活跃状态：目标正在进行中，关联的任务和 AI 会话正常运作】 */
    ACTIVE,
    /** 【暂停状态：用户暂时搁置该目标，可随时恢复为 ACTIVE】 */
    PAUSED,
    /** 【已达成状态：目标已经完成，可查看历史但不再接受新任务】 */
    ACHIEVED,
    /** 【已放弃状态：用户主动放弃该目标】 */
    ABANDONED,
    /** 【已归档状态：目标被归档保存，从活跃列表中隐藏但数据保留】 */
    ARCHIVED
}
