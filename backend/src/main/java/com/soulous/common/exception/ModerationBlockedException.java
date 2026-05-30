package com.soulous.common.exception;

import java.util.List;

/**
 * 【内容审核拦截异常 —— 当 AI 内容审核（Moderation）检测到用户输入或 AI 输出
 * 违反内容策略时抛出。全局异常处理器将其映射为 HTTP 422 (Unprocessable Entity)，
 * 表示请求语法合法但内容被拒绝。包含被触发的违规类别列表，便于前端展示具体原因。】
 *
 * <p>Thrown when AI moderation flags user input (or assistant output) as policy-violating.
 * Mapped to HTTP 422 (Unprocessable Entity) — the request was syntactically valid but
 * its content was rejected.</p>
 */
public class ModerationBlockedException extends RuntimeException {
    /** 【被触发的违规类别列表，如 "violence"、"hate" 等，用于前端提示和日志分析】 */
    private final List<String> categories;

    /**
     * 【构造内容审核拦截异常】
     *
     * @param message    【异常的可读描述信息】
     * @param categories 【被触发的违规类别列表，可为 null（内部会转为空列表）】
     */
    public ModerationBlockedException(String message, List<String> categories) {
        super(message);
        this.categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /**
     * 【获取被触发的违规类别列表（不可变列表）】
     *
     * @return 【违规类别列表】
     */
    public List<String> getCategories() {
        return categories;
    }
}
