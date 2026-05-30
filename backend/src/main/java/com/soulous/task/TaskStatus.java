package com.soulous.task;

/**
 * 【学习任务的生命周期状态枚举，定义了任务从创建到完成的完整状态流转】
 *
 * 【状态流转路径：
 * TODO → DOING → SUBMITTED → AI_APPROVED/AI_REJECTED/NEED_MORE → COMPLETED
 * 其中 DOING 和 PAUSED 可互相切换，NEED_MORE/AI_REJECTED 可重新提交，
 * APPEALING 为用户申诉中状态，MODERATION_BLOCKED 为内容安全拦截。】
 */
public enum TaskStatus {
    /** 【待办：任务刚创建，尚未开始】 */
    TODO,
    /** 【进行中：用户已点击"开始"，正在执行学习任务】 */
    DOING,
    /** 【已暂停：用户主动暂停任务，可随时恢复为 DOING】 */
    PAUSED,
    /** 【已提交：用户提交了学习证明，等待 AI 审核】 */
    SUBMITTED,
    /** 【AI 审核通过：任务完成，经验值已发放】 */
    AI_APPROVED,
    /** 【AI 审核拒绝：提交未通过 AI 审核，用户可修改后重新提交】 */
    AI_REJECTED,
    /** 【需要补充材料：AI 认为提交不够完整，需要用户补充更多证明】 */
    NEED_MORE,
    /** 【申诉中：用户对 AI 审核结果提出申诉，等待人工审核】 */
    APPEALING,
    /** 【人工审核通过：管理员覆盖 AI 决定，手动通过】 */
    MANUAL_APPROVED,
    /** 【人工审核拒绝：管理员覆盖 AI 决定，手动拒绝】 */
    MANUAL_REJECTED,
    /** 【已完成：任务终态，学习目标达成】 */
    COMPLETED,
    /** 【内容安全拦截：提交内容触发了敏感词过滤，需管理员介入】 */
    MODERATION_BLOCKED
}
