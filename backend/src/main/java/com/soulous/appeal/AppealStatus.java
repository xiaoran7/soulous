package com.soulous.appeal;

/**
 * 【申诉状态枚举，定义申诉记录的生命周期状态。
 * <ul>
 *   <li>PENDING - 待审核：申诉已提交，等待管理员处理</li>
 *   <li>APPROVED - 已通过：管理员审核通过，用户申诉成功</li>
 *   <li>REJECTED - 已驳回：管理员审核不通过，申诉被拒绝</li>
 *   <li>NEED_MORE - 需补充：管理员要求用户提供更多证据或信息</li>
 * </ul>】
 */
public enum AppealStatus {
    /** 【待审核】 */
    PENDING,
    /** 【已通过】 */
    APPROVED,
    /** 【已驳回】 */
    REJECTED,
    /** 【需补充材料】 */
    NEED_MORE
}
