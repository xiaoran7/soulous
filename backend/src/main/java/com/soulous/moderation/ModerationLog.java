package com.soulous.moderation;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.time.LocalDateTime;

/**
 * 【审核日志实体】
 * 持久化的审核评估审计记录。默认仅持久化 FLAG 和 BLOCK 判定（PASS 过于频繁会产生大量日志）。
 * 用于事后审查、惯犯检测和安全分析。
 *
 * <p>English: Persistent audit trail of moderation evaluations.
 * Only FLAG and BLOCK verdicts are persisted by default (PASS is too noisy).</p>
 */
@Entity
public class ModerationLog {
    /** 【主键 ID】自增主键。 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【关联用户】多对一关联，可空（某些场景下可能无法关联到具体用户）。 */
    @ManyToOne
    public UserAccount user;

    /**
     * 【会话 ID】发生此事件的会话 ID（可空——并非所有 LLM 调用都在会话中发生）。
     * English: The session where this event occurred (nullable — not all LLM calls happen in a session).
     */
    public Long sessionId;

    /** 【审核判定等级】PASS / FLAG / BLOCK，以字符串形式存储。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    public ModerationVerdict verdict;

    /** 【风险分数】0-100 的风险评分，0 表示完全安全，100 表示最高风险。 */
    @Column(nullable = false)
    public int riskScore;

    /** 【违规类别】主要违规类别枚举，以字符串形式存储。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public ModerationCategory category;

    /** 【判定原因】人类可读的判定原因说明（中文），可能展示给管理员或记录到日志。 */
    @Column(columnDefinition = "TEXT")
    public String reason;

    /** 【评估方向】INPUT（用户输入）或 OUTPUT（AI 输出），标识被评估内容的方向。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    public ModerationResult.Target target;

    /**
     * 【被评估内容】实际被评估的内容文本（截断为 2000 字符以节省存储空间）。
     * English: The content that was evaluated (truncated to 2000 chars for storage).
     */
    @Column(columnDefinition = "TEXT")
    public String evaluatedContent;

    /**
     * 【对话上下文片段】评估时使用的对话上下文片段。
     * English: Conversation context snippet used for the evaluation.
     */
    @Column(columnDefinition = "TEXT")
    public String contextSnippet;

    /** 【创建时间】日志记录的创建时间戳。 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
