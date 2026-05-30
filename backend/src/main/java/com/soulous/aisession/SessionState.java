package com.soulous.aisession;

/**
 * 【规划会话生命周期状态枚举：定义会话从创建到结束的完整状态流转】
 *
 * <p>状态流转：DRAFTING → PLAN_PROPOSED → COMMITTED（成功落地）| ABANDONED（用户放弃）| CLOSED（超时或强制关闭）。</p>
 *
 * <p>English: Lifecycle of a planning session:
 * DRAFTING → PLAN_PROPOSED → COMMITTED (success) | ABANDONED | CLOSED (timeout / forced).</p>
 *
 * <p>An earlier draft of the workflow had a separate {@code COMMITMENT} step between
 * PLAN_PROPOSED and COMMITTED where the user picks a tier (light/standard/ambitious).
 * That intermediate state was never actually assigned in the service code, so it was
 * unreachable dead code; the tier is now a parameter on the PLAN_PROPOSED → COMMITTED
 * transition directly. Don't reintroduce it without wiring the assignment as well.</p>
 */
public enum SessionState {
    /** 【起草中：用户与 AI 正在对话讨论，尚未生成最终计划】 */
    DRAFTING,
    /** 【计划已提出：AI 已输出 PLAN_JSON 草案，用户可编辑任务卡片后确认或继续调整】 */
    PLAN_PROPOSED,
    /** 【已确认：用户点击"确认计划"，任务已持久化到数据库，会话结束】 */
    COMMITTED,
    /** 【已放弃：用户主动放弃本次会话，不会产生任何任务】 */
    ABANDONED,
    /** 【已关闭：因对话轮次超限或底层目标失效等原因被系统强制关闭】 */
    CLOSED
}
