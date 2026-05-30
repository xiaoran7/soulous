package com.soulous.moderation;

/**
 * 【内容违规类别枚举】
 * 审核管线检测到的内容违规类别分类。每个类别对应一种特定的安全风险类型。
 *
 * <p>English: Categories of content violations detected by the moderation pipeline.</p>
 */
public enum ModerationCategory {
    /**
     * 【越狱攻击】试图覆盖、忽略或操纵系统指令的行为。
     * English: Attempts to override, ignore, or manipulate system instructions.
     */
    JAILBREAK,

    /**
     * 【提示词注入】在输入中嵌入隐藏指令，试图让模型执行非预期行为。
     * English: Injecting hidden instructions for the model to follow.
     */
    PROMPT_INJECTION,

    /**
     * 【有害内容】涉及暴力、自残、色情或其他有害信息的内容。
     * English: Violent, self-harm, sexual, or otherwise harmful content.
     */
    HARMFUL_CONTENT,

    /**
     * 【隐私泄露】个人身份信息的泄露或诱导收集行为。
     * English: Personally identifiable information leakage or solicitation.
     */
    PRIVACY_VIOLATION,

    /**
     * 【上下文渐进升级】多轮对话中逐步升级的攻击——单独看每轮无害，但整体构成恶意意图。
     * English: Gradual escalation across turns — individually benign, collectively malicious.
     */
    CONTEXTUAL_ESCALATION,

    /**
     * 【偏离主题滥用】与学习无关的内容，试图将学习 AI 当作通用工具滥用。
     * English: Content unrelated to learning that attempts to misuse the AI.
     */
    OFF_TOPIC_ABUSE,

    /**
     * 【无违规】判定为 PASS 时使用。
     * English: None — used when verdict is PASS.
     */
    NONE
}
