package com.soulous.moderation;

/**
 * 【审核判定结果枚举】
 * 内容审核检查的三种可能结果，代表不同的安全等级和处理方式。
 *
 * <ul>
 *   <li>{@code PASS} — 【通过】内容安全；正常继续。</li>
 *   <li>{@code FLAG} — 【标记】可疑但不确定；记录日志并可选告警，谨慎继续。</li>
 *   <li>{@code BLOCK} — 【阻止】明显违规；拒绝内容并通知用户。</li>
 * </ul>
 *
 * <p>English: Outcome of a moderation check.</p>
 *
 * <ul>
 *   <li>English: {@code PASS}  — content is safe; proceed normally.</li>
 *   <li>English: {@code FLAG}  — suspicious but not conclusive; log and optionally alert, proceed with caution.</li>
 *   <li>English: {@code BLOCK} — clearly violating; reject the content and notify the user.</li>
 * </ul>
 */
public enum ModerationVerdict {
    /** 【通过】内容安全，正常继续处理。 */
    PASS,

    /** 【标记】可疑但不确定，记录日志并谨慎处理。 */
    FLAG,

    /** 【阻止】明显违规，拒绝内容并通知用户。 */
    BLOCK
}
