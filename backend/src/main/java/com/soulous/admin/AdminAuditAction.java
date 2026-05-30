package com.soulous.admin;

/**
 * 【管理员审核操作类型枚举，定义管理员可以执行的审核操作。
 * <ul>
 *   <li>APPROVE - 批准：审核通过用户的任务提交，发放经验值奖励</li>
 *   <li>REJECT - 驳回：审核不通过用户的任务提交</li>
 *   <li>NEED_MORE - 需补充：要求用户提供更多证据或信息</li>
 *   <li>APPEAL_REVIEW - 申诉审核：对用户的申诉请求进行审核处理</li>
 * </ul>】
 */
public enum AdminAuditAction {
    /** 【批准】 */
    APPROVE,
    /** 【驳回】 */
    REJECT,
    /** 【需补充材料】 */
    NEED_MORE,
    /** 【申诉审核】 */
    APPEAL_REVIEW
}
