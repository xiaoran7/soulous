package com.soulous.rag;

/**
 * 【检索命中结果】
 * 表示一条被检索到的记忆片段及其相似度分数。作为不可变视图传递给上层调用方（通常是提示词构建器）。
 *
 * @param sourceType 【来源类型】片段的来源类型，同时驱动渲染提示词中的标签文本
 * @param sourceId   【来源 ID】原始实体的 ID（Goal / PlanningSession / StudyTask）
 * @param content    【内容】被索引的人类可读文本片段
 * @param similarity 【相似度】余弦相似度分数，范围 [-1, 1]，值越高越相关
 *
 * <p>English: One retrieved memory chunk with its similarity score. Immutable view passed up to callers
 * (typically the prompt builder).</p>
 *
 * <p>English: @param sourceType where the snippet came from — also drives the label in the rendered prompt</p>
 * <p>English: @param sourceId   id of the original entity (Goal/PlanningSession/StudyTask)</p>
 * <p>English: @param content    human-readable snippet that was indexed</p>
 * <p>English: @param similarity cosine score in [-1, 1]; higher is more relevant</p>
 */
public record RetrievalHit(
        EmbeddingSourceType sourceType,
        Long sourceId,
        String content,
        double similarity
) {}
