package com.soulous.ai;

import jakarta.validation.constraints.NotBlank;

/**
 * 【AI 模块的数据传输对象（DTO）集合。
 *
 * <p>集中定义 AI 相关接口的请求体 record，供 Controller 层参数绑定和校验使用。</p>
 */

/**
 * 【任务分解请求 DTO。
 *
 * <p>用户向 AI 提交高阶学习目标时使用，{@code goal} 字段携带自然语言描述的目标。</p>
 */
record DecomposeRequest(
    /** 【用户输入的学习目标，不能为空白；由 Jakarta Validation @NotBlank 校验】 */
    @NotBlank String goal
) {}

/**
 * 【AI 答疑/审核请求 DTO。
 *
 * <p>用于用户提交任务完成证据或向 AI 提问的场景。</p>
 */
record AiAnswerRequest(
    /** 【关联的任务提交 ID，标识此次回答对应哪次提交；可为 null（如纯答疑场景）】 */
    Long submissionId,

    /** 【用户的回答文本或提交的证据内容】 */
    String answer
) {}
