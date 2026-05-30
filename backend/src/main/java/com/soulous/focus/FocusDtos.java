package com.soulous.focus;

/**
 * 【专注会话相关的数据传输对象（DTO）：封装专注会话的请求参数。】
 */

/**
 * 【创建专注会话请求：开始新的专注计时时使用。】
 *
 * @param title 【专注会话标题，不能为空，描述本次专注的内容】
 * @param plannedMinutes 【计划专注时长（分钟），默认25分钟（番茄钟），必须为正数】
 * @param taskId 【可选的关联学习任务 ID，关联后专注完成时会更新任务实际用时】
 */
record FocusSessionRequest(String title, Integer plannedMinutes, Long taskId) {}

/**
 * 【完成专注会话请求：结束专注计时时使用。】
 *
 * @param outcome 【结束方式："aborted" 表示中止（不发放经验），其他值表示正常完成】
 */
record FocusFinishRequest(String outcome) {}
