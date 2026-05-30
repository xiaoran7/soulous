package com.soulous.moderation;

/**
 * 【审核结果记录】
 * 单次审核评估的不可变结果。作为审核管线的输出传递给调用方。
 *
 * @param verdict   【判定等级】PASS（通过）/ FLAG（标记）/ BLOCK（阻止）
 * @param riskScore 【风险分数】0-100（0 = 完全安全，100 = 最高风险）
 * @param category  【违规类别】主要违规类别（安全时为 NONE）
 * @param reason    【判定原因】人类可读的中文说明；可能展示给管理员或记录到日志
 * @param target    【评估方向】INPUT 或 OUTPUT——标识被评估内容的方向
 *
 * <p>English: Immutable result of a single moderation evaluation.</p>
 *
 * <p>English: @param verdict   PASS / FLAG / BLOCK</p>
 * <p>English: @param riskScore 0–100 (0 = perfectly safe, 100 = maximum risk)</p>
 * <p>English: @param category  the primary violation category (NONE when safe)</p>
 * <p>English: @param reason    human-readable explanation (Chinese); may be shown to admins or logged</p>
 * <p>English: @param target    INPUT or OUTPUT — which direction was evaluated</p>
 */
public record ModerationResult(
        ModerationVerdict verdict,
        int riskScore,
        ModerationCategory category,
        String reason,
        Target target
) {
    /**
     * 【评估方向枚举】
     * INPUT = 用户输入方向，OUTPUT = AI 输出方向。
     */
    public enum Target { INPUT, OUTPUT }

    /**
     * 【便捷工厂方法：通过】创建一个清洁通过的审核结果。
     * @param target 评估方向
     * @return PASS 结果
     * English: Convenience factory for a clean pass.
     */
    public static ModerationResult pass(Target target) {
        return new ModerationResult(ModerationVerdict.PASS, 0, ModerationCategory.NONE, "", target);
    }

    /**
     * 【便捷工厂方法：阻止】创建一个基于规则的阻止结果（无需 LLM 调用）。
     * @param category 违规类别
     * @param reason   阻止原因
     * @param target   评估方向
     * @return BLOCK 结果，风险分数为 100
     * English: Convenience factory for rule-based blocks (no LLM needed).
     */
    public static ModerationResult block(ModerationCategory category, String reason, Target target) {
        return new ModerationResult(ModerationVerdict.BLOCK, 100, category, reason, target);
    }

    /** 【是否被阻止】判定等级为 BLOCK 时返回 true。 */
    public boolean blocked() { return verdict == ModerationVerdict.BLOCK; }

    /** 【是否被标记】判定等级为 FLAG 时返回 true。 */
    public boolean flagged() { return verdict == ModerationVerdict.FLAG; }

    /** 【是否通过】判定等级为 PASS 时返回 true。 */
    public boolean passed()  { return verdict == ModerationVerdict.PASS; }
}
