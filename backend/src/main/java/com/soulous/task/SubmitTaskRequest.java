package com.soulous.task;

import java.util.List;

/**
 * 【任务提交请求 DTO（数据传输对象）——使用 Java 16 record 定义的不可变数据载体。
 *  封装用户提交任务时提供的各种证明材料，包括文本证明、代码片段、链接、学习时长和截图等。
 *  由前端通过 JSON 提交，Spring 自动反序列化后传递给 TaskService 处理。】
 *
 * <p>English: Request payload for submitting a task. Captures the various
 * proof types a user can provide when completing a study task.</p>
 *
 * @param textProof     【文本证明——用户以文字形式描述完成任务的过程或成果】
 * @param codeSnippet   【代码片段——适用于编程类任务，用户提交的代码内容】
 * @param proofLink     【证明链接——用户提供的外部资源链接，如博客文章、GitHub 仓库等】
 * @param studyMinutes  【学习时长（分钟）——用户记录本次学习所花费的时间】
 * @param screenshotUrl 【单张截图 URL——用户上传的证明截图地址（兼容旧版单图）】
 * @param screenshotUrls【多张截图 URL 列表——支持用户一次提交多张证明截图】
 */
public record SubmitTaskRequest(
        String textProof,
        String codeSnippet,
        String proofLink,
        Integer studyMinutes,
        String screenshotUrl,
        List<String> screenshotUrls
) {}
