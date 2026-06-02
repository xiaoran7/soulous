package com.soulous.goal;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 【目标相关的数据传输对象（DTO）集合类：封装目标操作中使用的请求和响应对象。
 *  使用 Java record 实现不可变数据对象，减少样板代码。】
 */
public final class GoalDtos {
    private GoalDtos() {}

    /**
     * 【更新目标请求体：支持部分更新，所有字段都是可选的。
     *  通过设置非 null 字段来更新对应属性，clearTargetDate 用于清除目标日期。】
     *
     * @param title 【目标标题，最长200字符，使用 @Size 校验】
     * @param targetDate 【目标日期，设置新的目标完成日期】
     * @param status 【目标状态，可修改为 ACTIVE、PAUSED、ACHIEVED、ABANDONED、ARCHIVED】
     * @param clearTargetDate 【是否清除目标日期，设为 true 时 targetDate 被置为 null】
     */
    public record UpdateGoalRequest(
            @Size(max = 200) String title,
            LocalDate targetDate,
            GoalStatus status,
            Boolean clearTargetDate
    ) {}

    /**
     * 【更新目标记忆请求体：用于在「目标工作台 · 设置」中导入/覆盖 distilled memory。
     *  memoryJson 必须是合法 JSON 字符串，服务端会校验后存入 Goal.distilledMemoryJson。】
     *
     * @param memoryJson 【蒸馏记忆 JSON 文本，最长 20000 字符】
     */
    public record UpdateMemoryRequest(
            @Size(max = 20000) String memoryJson
    ) {}

    /**
     * 【删除目标响应体：返回删除操作的统计结果。】
     *
     * @param id 【被删除的目标 ID】
     * @param unboundTasks 【解除绑定的任务数量（任务本身未删除，仅解除了与目标的关联）】
     * @param closedSessions 【关闭的 AI 规划会话数量（会话及其对话记录被级联删除）】
     */
    public record DeleteResult(long id, int unboundTasks, int closedSessions) {}
}
