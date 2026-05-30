package com.soulous.rag;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 【嵌入向量实体】
 * 表示属于某个用户的一条已索引文本片段及其向量表示。
 *
 * <p>向量以逗号分隔的浮点数形式序列化存储在 TEXT 列中，以保证跨数据库的可移植性
 * （H2/MySQL 兼容，无二进制编码问题）。约 768 个浮点数 × 每个约 12 个字符 ≈ 每行约 9 KB，
 * 对于我们目标的语料库规模来说完全可接受。如果每用户超过约 10 万行，
 * 将需要迁移到 pgvector + ANN 索引，但那是数据迁移而非架构重设计。</p>
 *
 * <p>{@code sourceType + sourceId} 共同标识该行数据的来源，
 * 使我们能在底层数据变更时（例如签到后蒸馏记忆被重写）进行原地重新索引。</p>
 *
 * <p>English: One indexed text snippet belonging to a user.</p>
 *
 * <p>English: The vector is serialised as comma-separated floats into a TEXT column for portability
 * (H2/MySQL agree, no binary encoding gotchas). At ~768 floats × ~12 chars each ≈ 9 KB per
 * row, which is fine for the corpus sizes we target. If we ever cross ~100k rows per user
 * we'll want pgvector + ANN indexing, but that's a migration not a redesign.</p>
 *
 * <p>English: {@code sourceType + sourceId} together identify what the row was derived from, which
 * lets us re-index in place when the underlying artifact changes (e.g. distilled memory got
 * rewritten after a check-in).</p>
 */
@Entity
@Table(
        name = "memory_embedding",
        indexes = {
                @Index(name = "idx_memembed_user", columnList = "user_id"),
                @Index(name = "idx_memembed_source", columnList = "source_type,source_id")
        }
)
public class MemoryEmbedding {
    /** 【主键 ID】自增主键。 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属用户】多对一关联，不允许为空。 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【来源类型】嵌入来源的枚举类型，以字符串形式存储。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, name = "source_type")
    public EmbeddingSourceType sourceType;

    /**
     * 【来源 ID】源实体的 ID（根据 sourceType 的不同，对应 Goal.id / PlanningSession.id / StudyTask.id）。
     * English: ID of the source entity (Goal.id / PlanningSession.id / StudyTask.id depending on type).
     */
    @Column(nullable = false, name = "source_id")
    public Long sourceId;

    /**
     * 【嵌入文本内容】被嵌入的人类可读文本。存储以便在检索命中时渲染展示。
     * English: Human-readable text that was embedded. Stored so we can render it in retrieved-hits.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    public String content;

    /**
     * 【向量维度】{@link #vector} 的维度数。存储以便在切换嵌入提供商时进行一致性校验。
     * English: Dimension of {@link #vector}. Stored so we can sanity-check provider switches.
     */
    @Column(nullable = false)
    public int dimension;

    /**
     * 【向量数据】逗号分隔的浮点数值。格式详见类文档。
     * English: Comma-separated float values. See class doc for the format choice.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    public String vector;

    /**
     * 【嵌入模型标识】生成此向量的嵌入提供商/模型名称，格式为 "provider:model"。
     * English: Name of the embedding provider/model that produced this vector.
     */
    @Column(nullable = false, length = 64, name = "embedded_with")
    public String embeddedWith;

    /** 【创建时间】记录首次创建的时间戳。 */
    public LocalDateTime createdAt = LocalDateTime.now();

    /** 【更新时间】记录最近一次更新的时间戳，用于时间衰减计算。 */
    public LocalDateTime updatedAt = LocalDateTime.now();
}
