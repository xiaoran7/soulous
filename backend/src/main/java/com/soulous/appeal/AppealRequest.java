package com.soulous.appeal;

import java.util.List;

/**
 * 【申诉请求的数据传输对象（DTO），使用 Java record 定义。
 * 封装用户提交申诉时所需的参数：关联的提交ID、申诉原因、截图URL列表。】
 *
 * @param submissionId   【要申诉的任务提交记录ID】
 * @param appealReason   【用户填写的申诉原因/说明】
 * @param screenshotUrls 【可选的截图证据URL列表，用于佐证申诉理由】
 */
public record AppealRequest(Long submissionId, String appealReason, List<String> screenshotUrls) {}
