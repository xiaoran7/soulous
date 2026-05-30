package com.soulous.ai;

import com.soulous.task.TaskSubmission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 【AI 审阅结果实体，持久化存储 LLM 对任务提交内容的审阅评分与建议。
 * 与 TaskSubmission 为一对一关系，每次提交只会产生一条 AI 审阅记录。
 * 包含总分、相关性/完整性/质量等维度分数，以及是否需要人工复审的标记。】
 */
@Entity
public class AiReview {
    /** 【主键，自增 ID】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【关联的任务提交记录，不允许为空——每条审阅必须对应一次提交】 */
    @OneToOne(optional = false)
    public TaskSubmission submission;

    /** 【审阅结果枚举（如 APPROVED / REJECTED），以字符串形式存储到数据库，方便阅读】 */
    @Enumerated(EnumType.STRING)
    public AiReviewResult result;

    /** 【总评分（0-100），由 LLM 综合各维度给出】 */
    public Integer score;

    /** 【相关性评分：提交内容与任务目标的匹配程度】 */
    public Integer relevanceScore;

    /** 【完整性评分：提交内容是否覆盖了任务要求的所有要点】 */
    public Integer completenessScore;

    /** 【质量评分：提交内容的表达质量、代码质量等】 */
    public Integer qualityScore;

    /** 【AI 给出的审阅理由，TEXT 类型支持长文本】 */
    @Column(columnDefinition = "TEXT")
    public String reason;

    /** 【AI 给出的改进建议，TEXT 类型支持长文本】 */
    @Column(columnDefinition = "TEXT")
    public String suggestion;

    /** 【AI 推荐的经验值奖励，根据审阅结果计算】 */
    public Integer recommendedExp;

    /** 【是否需要人工复审，默认 false。当 AI 判定内容存在歧义或低置信度时置为 true】 */
    public Boolean needManual = false;

    /** 【记录创建时间，默认取当前时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 【瞬态字段：RAG 检索命中记录列表。不会持久化到数据库，
     * 仅在 API 响应中返回，供前端展示"AI 参考了 N 条历史记忆"。
     * 当 RAG 功能禁用、无命中结果、或使用规则兜底逻辑时为空列表。
     * 每条记录包含：sourceType（来源类型）、sourceId（来源 ID）、similarity（相似度）、snippet（文本片段）。】
     *
     * <p>Transient list of RAG hits the LLM saw while producing this review. Not persisted —
     * surfaced in the response so the UI can show "AI 参考了 N 条历史记忆". Empty when RAG
     * disabled, returned no hits, or this review came from the rule-based fallback.
     * Each entry: {sourceType, sourceId, similarity, snippet}.</p>
     */
    @Transient
    public List<Map<String, Object>> ragHits = List.of();
}
