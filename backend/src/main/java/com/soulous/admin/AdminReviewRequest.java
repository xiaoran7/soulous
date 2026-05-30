package com.soulous.admin;

/**
 * 【管理员审核请求的数据传输对象（DTO），使用 Java record 定义。
 * 封装管理员执行审核操作时的参数：经验值奖励和审核评论。】
 *
 * @param expAmount 【可选的经验值数量，批准时使用；为 null 时使用任务默认基础经验值】
 * @param comment   【管理员的审核意见/评论】
 */
public record AdminReviewRequest(Integer expAmount, String comment) {}
