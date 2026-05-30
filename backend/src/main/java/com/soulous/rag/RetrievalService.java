package com.soulous.rag;

import com.soulous.ai.embedding.EmbeddingService;
import com.soulous.auth.UserAccount;
import com.soulous.task.StudyTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 【检索增强生成（RAG）核心服务】
 * 负责 {@link MemoryEmbedding} 的索引与检索。本服务是 RAG 模块的核心组件，
 * 对外提供幂等的向量写入和基于余弦相似度的 Top-K 检索能力。
 *
 * <h3>索引能力</h3>
 * <ul>
 *   <li>{@link #indexOrUpdate(UserAccount, EmbeddingSourceType, Long, String)} 是幂等的 upsert 操作——
 *       使用相同的 (user, sourceType, sourceId) 重复调用会覆盖旧行。</li>
 *   <li>{@link #remove(UserAccount, EmbeddingSourceType, Long)} 删除过时条目（例如目标被硬删除时）。</li>
 * </ul>
 *
 * <h3>检索能力</h3>
 * <ul>
 *   <li>{@link #retrieve(UserAccount, String, int, double)} 将查询文本向量化，扫描该用户已存储的向量，
 *       在 Java 内存中计算余弦相似度，返回超过阈值的 Top-K 结果。</li>
 *   <li>当每个用户的语料库规模低于约 1 万条时，内存扫描性能足够（毫秒级）。
 *       超过该规模后需要迁移到 pgvector + ANN 索引。</li>
 * </ul>
 *
 * <p>当 {@code soulous.rag.enabled=false} 时，所有方法均为空操作（no-op），
 * 调用方无需做条件判断，运维人员可通过配置开关统一控制 RAG 功能的激活。</p>
 *
 * <p>English: Indexing + retrieval of {@link MemoryEmbedding}s.</p>
 *
 * <h3>English: Indexing</h3>
 * <ul>
 *   <li>{@link #indexOrUpdate(UserAccount, EmbeddingSourceType, Long, String)} is idempotent —
 *       called repeatedly with the same (user, sourceType, sourceId) it rewrites the row.</li>
 *   <li>{@link #remove(UserAccount, EmbeddingSourceType, Long)} drops a stale entry (e.g. when
 *       a goal is hard-deleted).</li>
 * </ul>
 *
 * <h3>English: Retrieval</h3>
 * <ul>
 *   <li>{@link #retrieve(UserAccount, String, int, double)} embeds the query, scans the user's
 *       stored vectors, computes cosine similarity in Java, returns top-K above threshold.</li>
 *   <li>For corpus sizes below ~10k per user this in-memory scan is fine (single-digit ms).
 *       Above that we'd want pgvector with an ANN index.</li>
 * </ul>
 *
 * <p>English: All methods are no-ops when {@code soulous.rag.enabled=false} so callers can wire RAG in
 * unconditionally and the operator controls activation via config.</p>
 */
@Service
public class RetrievalService {
    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** 【嵌入向量数据仓库】用于持久化和查询 MemoryEmbedding 实体。 */
    private final MemoryEmbeddingRepository repo;

    /** 【嵌入服务】负责将文本转换为向量，由外部 AI 模型提供商驱动。 */
    private final EmbeddingService embeddings;

    /** 【RAG 配置属性】绑定 soulous.rag.* 配置项，控制 RAG 功能的开关和参数。 */
    private final RagProperties props;

    /** 【系统时钟】用于时间衰减计算，测试时可注入固定时钟。 */
    private final Clock clock;

    /**
     * 【主构造器】由 Spring 自动注入依赖，使用系统默认时钟。
     *
     * @param repo       嵌入向量数据仓库
     * @param embeddings 嵌入服务
     * @param props      RAG 配置属性
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalService(MemoryEmbeddingRepository repo,
                            EmbeddingService embeddings,
                            RagProperties props) {
        this(repo, embeddings, props, Clock.systemDefaultZone());
    }

    /**
     * 【测试专用构造器】允许注入固定时钟，便于单元测试中控制时间行为。
     * English: Test-only constructor for injecting a fixed clock.
     */
    RetrievalService(MemoryEmbeddingRepository repo,
                     EmbeddingService embeddings,
                     RagProperties props,
                     Clock clock) {
        this.repo = repo;
        this.embeddings = embeddings;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 【检查 RAG 功能是否可用】
     * 需要同时满足两个条件：配置开关已启用 且 嵌入服务可用。
     *
     * @return true 表示 RAG 功能已就绪
     */
    public boolean isEnabled() {
        return props.isEnabled() && embeddings.isAvailable();
    }

    // ----- 索引操作 -----------------------------------------------------

    /**
     * 【幂等 upsert 操作】
     * 如果已存在 (user, sourceType, sourceId) 对应的行，则用新的内容/向量覆盖；
     * 否则创建新行。当 content 为 null 或空白时，会删除对应行——
     * 调用方无需额外分支处理"源数据被清空"的场景。
     *
     * @param user     所属用户
     * @param type     嵌入来源类型
     * @param sourceId 源实体的 ID
     * @param content  要嵌入的文本内容
     *
     * <p>English: Idempotent upsert: if a row exists for (user, sourceType, sourceId) it's rewritten with
     * the new content/vector; otherwise a new row is created. {@code null} or blank content
     * removes the row instead — so callers can pass through "the source got cleared" without
     * a separate branch.</p>
     */
    @Transactional
    public void indexOrUpdate(UserAccount user, EmbeddingSourceType type, Long sourceId, String content) {
        if (!isEnabled()) return;
        if (user == null || type == null || sourceId == null) return;

        if (content == null || content.isBlank()) {
            remove(user, type, sourceId);
            return;
        }

        var maybeVec = embeddings.embed(content);
        if (maybeVec.isEmpty()) return; // upstream already logged
        var vec = maybeVec.get();

        var existing = repo.findByUserAndSourceTypeAndSourceId(user, type, sourceId).orElse(null);
        var entity = existing == null ? new MemoryEmbedding() : existing;
        entity.user = user;
        entity.sourceType = type;
        entity.sourceId = sourceId;
        entity.content = content;
        entity.dimension = vec.length;
        entity.vector = serialize(vec);
        entity.embeddedWith = embeddings.providerName() + ":" + embeddings.model();
        entity.updatedAt = LocalDateTime.now();
        repo.save(entity);
    }

    /**
     * 【索引已完成任务的便捷方法】
     * 针对常见的"任务刚完成"场景，从任务的标题和描述构建摘要文本，
     * 并将其作为 COMPLETED_TASK 类型的嵌入进行 upsert。
     * 任何将任务状态翻转为 COMPLETED 的代码路径都可以安全调用此方法——
     * 当 RAG 禁用时为空操作，调用方无需做条件判断。
     *
     * @param task 已完成的学习任务
     *
     * <p>English: Convenience for the common "task just got completed" pattern. Builds a snippet from
     * title + description (skipping empty pieces) and upserts it as a COMPLETED_TASK row.
     * Safe to call from any code path that flips a task to COMPLETED — disabled-RAG is a
     * no-op and callers don't need to branch.</p>
     */
    @Transactional
    public void indexCompletedTask(StudyTask task) {
        if (task == null || task.user == null || task.id == null) return;
        var sb = new StringBuilder();
        if (task.title != null && !task.title.isBlank()) sb.append(task.title);
        if (task.description != null && !task.description.isBlank()) {
            if (sb.length() > 0) sb.append("：");
            sb.append(task.description);
        }
        indexOrUpdate(task.user, EmbeddingSourceType.COMPLETED_TASK, task.id, sb.toString());
    }

    /**
     * 【删除指定嵌入条目】
     * 根据用户、来源类型和来源 ID 删除对应的嵌入向量记录。
     *
     * @param user     所属用户
     * @param type     嵌入来源类型
     * @param sourceId 源实体的 ID
     */
    @Transactional
    public void remove(UserAccount user, EmbeddingSourceType type, Long sourceId) {
        if (user == null || type == null || sourceId == null) return;
        repo.deleteByUserAndSourceTypeAndSourceId(user, type, sourceId);
    }

    // ----- 检索操作 ----------------------------------------------------

    /**
     * 【基于余弦相似度的 Top-K 检索】
     * 将查询文本向量化后，扫描该用户的所有存储向量，计算余弦相似度，
     * 结合时间衰减因子进行排序，返回超过最低阈值的 Top-K 结果。
     * 当 RAG 禁用、语料库为空或嵌入失败时返回空列表——调用方必须容忍空结果。
     *
     * @param user          所属用户
     * @param query         查询文本
     * @param topK          最大返回数量；非正数时使用配置默认值
     * @param minSimilarity 最低相似度阈值；传 NaN 使用 {@link RagProperties#getMinSimilarity()}
     * @return 按相似度降序排列的检索命中列表
     *
     * <p>English: Top-K hits scored by cosine similarity, filtered by minimum threshold. Returns an empty
     * list when disabled / no corpus / embedding failure — callers must tolerate empty.</p>
     */
    public List<RetrievalHit> retrieve(UserAccount user, String query, int topK, double minSimilarity) {
        if (!isEnabled() || user == null || query == null || query.isBlank()) return List.of();

        var k = topK > 0 ? topK : props.getTopK();
        var threshold = Double.isNaN(minSimilarity) ? props.getMinSimilarity() : minSimilarity;

        var maybeQueryVec = embeddings.embed(query);
        if (maybeQueryVec.isEmpty()) return List.of();
        var queryVec = maybeQueryVec.get();
        var queryNorm = norm(queryVec);
        if (queryNorm == 0) return List.of();

        var rows = repo.findByUser(user);
        if (rows.isEmpty()) return List.of();

        var halfLifeDays = props.getHalfLifeDays();
        var now = LocalDateTime.now(clock);

        var scored = new ArrayList<RetrievalHit>(rows.size());
        for (var row : rows) {
            float[] vec;
            try {
                vec = deserialize(row.vector);
            } catch (Exception ex) {
                log.warn("Skipping malformed embedding id={}: {}", row.id, ex.getMessage());
                continue;
            }
            if (vec.length != queryVec.length) continue; // dimension mismatch — stale, ignore
            var rawSim = cosine(queryVec, queryNorm, vec);
            // Threshold against raw cosine so the operator's intuition for `min-similarity` is
            // preserved (it filters on semantic match, not on age). Ranking then uses the decayed
            // score so older but still-semantically-relevant memories are demoted.
            // 【阈值过滤使用原始余弦相似度，保持运维人员对 min-similarity 参数的直觉理解
            // （仅基于语义匹配过滤，不考虑时间因素）。排序时使用衰减后的分数，
            // 使较旧但仍语义相关的记忆在排名中靠后。】
            if (rawSim < threshold) continue;
            var refTime = row.updatedAt != null ? row.updatedAt : row.createdAt;
            var decayed = rawSim * timeDecay(refTime, now, halfLifeDays);
            scored.add(new RetrievalHit(row.sourceType, row.sourceId, row.content, decayed));
        }
        scored.sort(Comparator.comparingDouble(RetrievalHit::similarity).reversed());
        return scored.size() > k ? scored.subList(0, k) : scored;
    }

    /**
     * 【时间衰减计算】
     * 使用指数衰减公式 {@code 2^(-ageDays / halfLifeDays)} 计算时间衰减因子。
     * 当 halfLifeDays <= 0 时禁用衰减，始终返回 1.0。
     * 例如：halfLifeDays=90 时，3 个月前的记忆衰减为 50%，6 个月前衰减为 25%。
     *
     * @param memoryTime  记忆的创建/更新时间
     * @param now         当前时间
     * @param halfLifeDays 半衰期天数
     * @return 衰减因子，范围 (0, 1]
     *
     * <p>English: {@code 2^(-ageDays / halfLifeDays)}. Returns 1.0 when halfLifeDays {@code <= 0} (decay off).</p>
     */
    static double timeDecay(LocalDateTime memoryTime, LocalDateTime now, int halfLifeDays) {
        if (halfLifeDays <= 0 || memoryTime == null) return 1.0;
        var ageHours = Math.max(0, Duration.between(memoryTime, now).toHours());
        var ageDays = ageHours / 24.0;
        return Math.pow(0.5, ageDays / halfLifeDays);
    }

    /**
     * 【便捷检索方法】使用配置的默认 topK 和 minSimilarity 进行检索。
     * English: Convenience overload using configured defaults.
     */
    public List<RetrievalHit> retrieve(UserAccount user, String query) {
        return retrieve(user, query, 0, Double.NaN);
    }

    // ----- 余弦相似度计算 --------------------------------------------------

    /**
     * 【余弦相似度计算（带预计算查询范数）】
     * 当调用方已经持有查询向量的范数时使用此方法，避免每行重复计算。
     *
     * @param q     查询向量
     * @param qNorm 查询向量的 L2 范数
     * @param d     文档向量
     * @return 余弦相似度，范围 [-1, 1]
     *
     * <p>English: Cosine when caller already has the query's norm — avoids recomputing per-row.</p>
     */
    static double cosine(float[] q, double qNorm, float[] d) {
        var dot = 0.0;
        var dSumSq = 0.0;
        for (int i = 0; i < q.length; i++) {
            dot += q[i] * d[i];
            dSumSq += d[i] * d[i];
        }
        var denom = qNorm * Math.sqrt(dSumSq);
        return denom == 0 ? 0 : dot / denom;
    }

    /**
     * 【计算向量的 L2 范数】
     * 计算给定浮点数组的欧几里得范数（L2 范数）。
     *
     * @param v 输入向量
     * @return L2 范数值
     */
    static double norm(float[] v) {
        var s = 0.0;
        for (float x : v) s += x * x;
        return Math.sqrt(s);
    }

    // ----- 向量序列化/反序列化 ------------------------------------------------

    /**
     * 【向量序列化】将浮点数组序列化为逗号分隔的字符串，用于数据库存储。
     *
     * @param v 浮点向量
     * @return 逗号分隔的字符串表示
     */
    static String serialize(float[] v) {
        var sb = new StringBuilder(v.length * 12);
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.toString();
    }

    /**
     * 【向量反序列化】将逗号分隔的字符串反序列化为浮点数组。
     *
     * @param s 逗号分隔的字符串
     * @return 浮点向量；输入为 null 或空时返回空数组
     */
    static float[] deserialize(String s) {
        if (s == null || s.isEmpty()) return new float[0];
        var parts = s.split(",");
        var out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i]);
        return out;
    }
}
