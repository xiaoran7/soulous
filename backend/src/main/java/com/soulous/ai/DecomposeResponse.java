package com.soulous.ai;

import java.util.List;

/**
 * 【AI 任务分解接口的响应数据模型。
 *
 * <p>封装 AI 将用户目标拆解后的子任务列表。由 Controller 层返回给前端，
 * 前端据此渲染任务卡片或引导用户逐步完成。</p>
 *
 * <p>使用 Java record 定义，列表中的每个元素为 {@link DecomposedTask} 实例。</p>
 */
public record DecomposeResponse(
    /** 【AI 分解出的子任务列表，按推荐执行顺序排列】 */
    List<DecomposedTask> tasks
) {}
