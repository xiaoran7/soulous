package com.soulous.focus;

/**
 * 【专注会话状态枚举：描述专注计时会话的生命周期状态。
 *  状态流转：RUNNING → PAUSED（暂停）→ RUNNING（恢复）
 *           RUNNING → COMPLETED（完成）
 *           RUNNING → ABORTED（中止）
 *           PAUSED → COMPLETED（完成，从暂停直接结束）
 *           PAUSED → ABORTED（中止，从暂停直接中止）】
 */
public enum FocusStatus {
    /** 【运行中：专注计时器正在计时】 */
    RUNNING,
    /** 【暂停中：计时器冻结，不计入已用时间】 */
    PAUSED,
    /** 【已完成：正常结束专注，发放经验奖励】 */
    COMPLETED,
    /** 【已中止：提前结束专注，不发放经验奖励】 */
    ABORTED
}
