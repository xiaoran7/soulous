package com.soulous.ai.embedding;

/**
 * 【Embedding 服务的统一接口，定义了所有 embedding 后端（Ollama / OpenAI / Mock）必须实现的能力。
 * 将文本转换为稠密向量（float 数组），用于 RAG 检索中的语义相似度计算。
 * 实现类必须保证线程安全，因为同一实例可能被多个请求并发调用。】
 *
 * <p>One embedding backend (Ollama / OpenAI / Mock). Returns dense vectors for text.
 * Implementations must be safe for concurrent use.</p>
 */
public interface EmbeddingProvider {
    /**
     * 【返回此 provider 的逻辑名称，用于配置路由和日志标识。
     * 例如 "ollama-nomic"、"openai-3-small"、"mock"。】
     *
     * <p>Logical name (e.g. "ollama-nomic", "openai-3-small", "mock").</p>
     */
    String name();

    /**
     * 【返回 provider 侧的模型标识符，用于 API 调用时指定模型。
     * 例如 "nomic-embed-text"、"text-embedding-3-small"。】
     *
     * <p>Model identifier on the provider side.</p>
     */
    String model();

    /**
     * 【返回此 provider 生成的向量维度。
     * 用于存储向量时的维度校验——如果维度不匹配，说明配置有误。】
     *
     * <p>Vector dimension this provider produces. Used for sanity-checks on stored vectors.</p>
     */
    int dimension();

    /**
     * 【检查此 provider 是否可用（如本地 Ollama 是否启动、API 密钥是否配置）。
     * 返回 false 时调用方应尝试降级到其他 provider。】
     */
    boolean available();

    /**
     * 【将单段文本转换为稠密浮点向量。
     * 网络或 HTTP 错误时可能抛出异常，由调用方决定是否降级或重试。】
     *
     * @param text 【待嵌入的文本内容】
     * @return 【浮点数组表示的向量】
     * @throws Exception 【网络错误或服务端异常时抛出】
     *
     * <p>Embed a single piece of text. May throw on network/HTTP errors — caller decides fallback.</p>
     */
    float[] embed(String text) throws Exception;
}
