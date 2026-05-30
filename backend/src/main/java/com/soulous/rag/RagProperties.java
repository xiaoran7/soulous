package com.soulous.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 【RAG 模块配置属性】
 * 绑定 {@code soulous.rag.*} 前缀的配置项。RAG 功能采用"选择性启用"（opt-in）设计——
 * 当禁用时，索引和检索均为空操作，系统行为与未引入 RAG 之前完全一致。
 * 运维人员可通过环境变量或配置文件灵活控制各项参数。
 *
 * <p>English: Binds {@code soulous.rag.*}. RAG is opt-in — when disabled, both indexing and retrieval
 * are no-ops, so the system runs identically to its pre-RAG behavior.</p>
 */
@Component
@ConfigurationProperties(prefix = "soulous.rag")
public class RagProperties {
    /**
     * 【总开关】默认关闭，通过 {@code SOULOUS_RAG_ENABLED=true} 环境变量启用。
     * English: Master switch. Default OFF — opt-in via {@code SOULOUS_RAG_ENABLED=true}.
     */
    private boolean enabled = false;

    /**
     * 【Top-K 数量】注入到 LLM 提示词中的最大检索命中数。
     * English: Max number of hits to inject into the prompt.
     */
    private int topK = 3;

    /**
     * 【最低相似度阈值】余弦相似度低于此值的命中结果将被丢弃。
     * 取值范围 [-1, 1]，建议最低值约 0.65。
     * English: Cosine similarity threshold — hits below this are dropped (range [-1, 1]; ~0.65 is a sensible floor).
     */
    private double minSimilarity = 0.65;

    /**
     * 【单条命中最大字符数】每条检索结果渲染的最大字符数，超出部分将被截断。
     * English: Max chars to render per retrieved hit (longer hits get truncated).
     */
    private int hitMaxChars = 500;

    /**
     * 【时间衰减半衰期（天）】检索命中结果的半衰期天数。
     * 最终排序分数 = 余弦相似度 × 2^(-ageDays / halfLifeDays)，
     * 即恰好 halfLifeDays 天前的记忆贡献度为新鲜记忆的一半。
     * 设为 0 则完全禁用时间衰减。
     * 默认值 90：3 个月前的记忆排名权重为 50%，6 个月前为 25%，以此类推。
     *
     * <p>English: Half-life (in days) of a retrieval hit. Effective ranking score is
     * {@code cosineSim * 2^(-ageDays / halfLifeDays)}, so a memory exactly halfLifeDays
     * old contributes half as much as a fresh one. {@code 0} disables decay entirely.
     * Default 90: a 3-month-old memory ranks at 50%, 6-month at 25%, etc.</p>
     */
    private int halfLifeDays = 90;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public double getMinSimilarity() { return minSimilarity; }
    public void setMinSimilarity(double minSimilarity) { this.minSimilarity = minSimilarity; }
    public int getHitMaxChars() { return hitMaxChars; }
    public void setHitMaxChars(int hitMaxChars) { this.hitMaxChars = hitMaxChars; }
    public int getHalfLifeDays() { return halfLifeDays; }
    public void setHalfLifeDays(int halfLifeDays) { this.halfLifeDays = halfLifeDays; }
}
