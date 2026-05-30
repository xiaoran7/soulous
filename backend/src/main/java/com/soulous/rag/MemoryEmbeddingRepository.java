package com.soulous.rag;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 【嵌入向量数据仓库接口】
 * 继承 JpaRepository，提供 {@link MemoryEmbedding} 实体的 CRUD 操作。
 * 针对 RAG 模块的查询需求定义了自定义查询方法。
 * 在 MVP 阶段，每个用户的数据量足够小，可以直接在内存中进行余弦相似度扫描。
 */
public interface MemoryEmbeddingRepository extends JpaRepository<MemoryEmbedding, Long> {
    /**
     * 【查询用户所有嵌入记录】
     * 返回指定用户的所有嵌入向量行——在 MVP 规模下数据量足够小，
     * 可以内存扫描完成余弦 Top-K 计算。
     * English: All rows for one user — small enough to scan in memory for cosine top-K at MVP scale.
     */
    List<MemoryEmbedding> findByUser(UserAccount user);

    /**
     * 【按来源精确查询】
     * 根据用户、来源类型和来源 ID 精确查找嵌入记录，
     * 用于源数据变更时的原地重新索引。
     * English: Used for re-indexing in place when the source artifact changes.
     */
    Optional<MemoryEmbedding> findByUserAndSourceTypeAndSourceId(
            UserAccount user, EmbeddingSourceType sourceType, Long sourceId);

    /**
     * 【按来源精确删除】
     * 根据用户、来源类型和来源 ID 删除嵌入记录。
     *
     * @param user       所属用户
     * @param sourceType 嵌入来源类型
     * @param sourceId   源实体的 ID
     */
    void deleteByUserAndSourceTypeAndSourceId(
            UserAccount user, EmbeddingSourceType sourceType, Long sourceId);
}
