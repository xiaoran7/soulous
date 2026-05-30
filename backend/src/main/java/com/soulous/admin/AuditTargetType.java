package com.soulous.admin;

/**
 * 【审核目标类型枚举，定义管理员审核操作的目标实体类型。
 * <ul>
 *   <li>SUBMISSION - 任务提交：管理员对用户提交的任务完成情况进行审核</li>
 *   <li>APPEAL - 申诉：管理员对用户的申诉请求进行审核</li>
 * </ul>】
 */
public enum AuditTargetType {
    /** 【任务提交】 */
    SUBMISSION,
    /** 【申诉记录】 */
    APPEAL
}
