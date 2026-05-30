package com.soulous.ai.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 【基于哈希的伪 Embedding 实现，用于测试和离线开发环境。
 * 相同输入始终产生相同向量，不同输入趋向于产生不同向量，但不具备语义含义。
 *
 * 实现原理：对输入文本做 SHA-256 哈希，然后通过多轮哈希（拼接轮次号）将摘要字节扩展到目标维度。
 * 最终对向量做 L2 归一化，使余弦相似度计算有意义。
 *
 * 注意：此实现仅用于开发/测试，不应用于生产环境的语义检索。】
 *
 * <p>Deterministic hash-based pseudo-embedding. Same input → same vector, different inputs
 * tend to differ. Not semantically meaningful — purely for tests / dev without network.</p>
 *
 * <p>Works by hashing the input with SHA-256, then expanding the digest bytes into the target
 * dimension by repeatedly hashing concatenations. Values are normalised to unit length so
 * cosine similarity is well-defined.</p>
 */
public final class MockEmbeddingProvider implements EmbeddingProvider {
    private final int dimension;

    /**
     * 【构造函数：指定输出向量的维度。维度 <= 0 时默认使用 64。】
     */
    public MockEmbeddingProvider(int dimension) {
        this.dimension = dimension <= 0 ? 64 : dimension;
    }

    @Override public String name() { return "mock"; }
    @Override public String model() { return "mock-" + dimension; }
    @Override public int dimension() { return dimension; }

    /** 【Mock provider 始终可用，不依赖外部服务】 */
    @Override
    public boolean available() { return true; }

    /**
     * 【将文本转换为伪向量。
     * 通过多轮 SHA-256 哈希生成指定维度的浮点数组，每轮哈希产生 32 字节（32 个 float 值）。
     * 有符号字节 [-128, 127] 映射到 [-1.0, ~1.0] 范围。
     * 最后做 L2 归一化，使向量长度为 1，便于余弦相似度计算。】
     *
     * @param text 【待嵌入的文本，null 会被当作空字符串处理】
     * @return 【归一化后的浮点向量，维度与构造时指定的一致】
     */
    @Override
    public float[] embed(String text) {
        var seed = text == null ? "" : text;
        var vec = new float[dimension];
        try {
            var md = MessageDigest.getInstance("SHA-256");
            int filled = 0;
            int round = 0;
            while (filled < dimension) {
                md.reset();
                var input = (seed + "|" + round).getBytes(StandardCharsets.UTF_8);
                var digest = md.digest(input);
                for (int i = 0; i < digest.length && filled < dimension; i++) {
                    // Map signed byte [-128, 127] to [-1.0, ~1.0]
                    // 【将有符号字节 [-128, 127] 映射到 [-1.0, ~1.0] 浮点范围】
                    vec[filled++] = digest[i] / 128.0f;
                }
                round++;
            }
        } catch (Exception ex) {
            // SHA-256 is guaranteed available; treat as fatal config error
            // 【SHA-256 是 JDK 标准算法，必定可用；如果抛异常说明环境严重问题，视为致命错误】
            throw new IllegalStateException("MockEmbeddingProvider failed", ex);
        }
        return normalise(vec);
    }

    /**
     * 【L2 归一化：将向量缩放为单位长度（模为 1）。
     * 零向量直接返回，避免除零异常。】
     */
    private static float[] normalise(float[] v) {
        double sumSq = 0;
        for (float x : v) sumSq += x * x;
        var norm = Math.sqrt(sumSq);
        if (norm == 0) return v;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        return v;
    }
}
