package com.soulous.task;

/**
 * 【提交记录的生命周期状态枚举，定义了从提交到审核完成的完整状态流转】
 *
 * 【状态说明：
 * - PENDING：初始待处理状态
 * - AI_REVIEWING：AI 正在审核中的瞬态，由 {@code submit()} 在持久化后、
 *   异步 AI 审核触发前设置。此状态不能永久停留——异步审核任务总会写入终态
 *   （AI_APPROVED / AI_REJECTED / NEED_MORE / MODERATION_BLOCKED），
 *   硬故障时回退到 NEED_MORE 以便用户重新提交。
 * - AI_APPROVED：AI 审核通过
 * - AI_REJECTED：AI 审核拒绝
 * - NEED_MORE：需要补充材料
 * - MANUAL_APPROVED：人工审核通过（管理员操作）
 * - MANUAL_REJECTED：人工审核拒绝（管理员操作）
 * - MODERATION_BLOCKED：内容安全审核拦截
 * 前端将 AI_REVIEWING 显示为"审核中..."并附带加载动画。】
 *
 * <p>Lifecycle of a TaskSubmission.</p>
 *
 * <p>{@code AI_REVIEWING} is the transient state set by {@code submit()} after the row
 * is persisted but before the async AI review fires. It must not stay forever — the
 * {@code @Async} review task always writes a terminal status (AI_APPROVED / AI_REJECTED /
 * NEED_MORE / MODERATION_BLOCKED) or, on hard failure, falls back to NEED_MORE so the
 * user can resubmit. Frontend treats it as "审核中..." with a typing indicator.</p>
 */
public enum SubmissionStatus {
    /** 【待处理，初始状态】 */
    PENDING,
    /** 【AI 审核中，瞬态——异步审核完成后会转为终态】 */
    AI_REVIEWING,
    /** 【AI 审核通过，终态】 */
    AI_APPROVED,
    /** 【AI 审核拒绝，终态——用户可修改后重新提交】 */
    AI_REJECTED,
    /** 【需要补充材料，半终态——用户需补充内容后重新提交】 */
    NEED_MORE,
    /** 【人工审核通过，终态——由管理员手动操作】 */
    MANUAL_APPROVED,
    /** 【人工审核拒绝，终态——由管理员手动操作】 */
    MANUAL_REJECTED,
    /** 【内容安全审核拦截，终态——提交内容触发了敏感词过滤】 */
    MODERATION_BLOCKED
}
