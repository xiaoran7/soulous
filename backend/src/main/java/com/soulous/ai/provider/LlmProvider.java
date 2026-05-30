package com.soulous.ai.provider;

/**
 * 【LLM Provider 抽象接口，定义所有 LLM 后端必须实现的统一契约。
 * 采用策略模式（Strategy Pattern），使上层业务代码无需关心底层使用的是哪个 LLM 供应商。】
 *
 * <p>【设计要点：
 * <ul>
 *   <li>实现类必须是无状态的（w.r.t. 请求），确保线程安全和并发使用</li>
 *   <li>支持 OpenAI 兼容、Anthropic、本地 Mock 等多种后端</li>
 *   <li>默认提供 stream() 的回退实现（单次调用），旧 provider 无需实现 SSE 也能正常工作</li>
 * </ul>
 * </p>
 *
 * <p>English: One LLM backend (OpenAI-compatible / Anthropic / Ollama / mock).
 * Implementations must be stateless w.r.t. the request and safe for concurrent use.</p>
 */
public interface LlmProvider {
    /**
     * 【返回 provider 的逻辑名称，即注册时使用的标识符（如 "deepseek"、"ollama"、"claude"）。
     * 用于日志记录、路由选择和缓存 key 生成。】
     *
     * <p>English: Logical name as registered (e.g. "deepseek", "ollama", "claude").</p>
     */
    String name();

    /**
     * 【返回底层 API 族类型，决定请求/响应的 JSON 结构格式。
     * 可选值："openai"（兼容 DeepSeek/Moonshot/Ollama 等）、"anthropic"、"mock"。】
     *
     * <p>English: Underlying API family — drives request/response shape. "openai" | "anthropic" | "mock".</p>
     */
    String type();

    /**
     * 【返回此 provider 使用的模型标识符（如 "gpt-4o-mini"、"deepseek-chat"），
     * 用于缓存 key 生成和遥测标签。】
     */
    String model();

    /**
     * 【检查此 provider 当前是否可用。不可用时 complete() 和 stream() 不应被调用。
     * 通常检查 API key 是否配置、本地服务是否运行等。】
     */
    boolean available();

    /**
     * 【同步阻塞式文本完成。将 system prompt 和 user prompt 发送给 LLM，
     * 等待完整响应后返回生成的文本。】
     *
     * @param systemPrompt 【系统提示词，定义 AI 的角色和行为约束】
     * @param userPrompt   【用户提示词，即实际的问题或指令】
     * @return 【LLM 生成的完整文本回复】
     * @throws Exception   【API 调用失败、网络错误或响应解析异常时抛出】
     */
    String complete(String systemPrompt, String userPrompt) throws Exception;

    /**
     * 【带 JSON 模式开关的文本完成。jsonMode 为 true 时，实现应请求后端返回严格 JSON
     * （如 OpenAI 兼容协议的 response_format:{"type":"json_object"}），显著提升 JSON 可靠性。
     * 默认实现忽略该开关、委托回 {@link #complete(String, String)}——这样 Mock / Anthropic 等
     * 尚未支持原生 JSON 模式的 provider 无需改动，测试子类覆写 complete 也不受影响。】
     *
     * <p>English: JSON-mode variant. When {@code jsonMode} is true, implementations should ask the
     * backend for strict JSON (e.g. OpenAI's {@code response_format}). Default ignores the flag and
     * delegates to {@link #complete(String, String)} so providers without native JSON support — and
     * test subclasses overriding {@code complete} — keep working unchanged.</p>
     *
     * @param systemPrompt 【系统提示词】
     * @param userPrompt   【用户提示词】
     * @param jsonMode     【为 true 时请求严格 JSON 输出】
     * @return 【LLM 生成的完整文本】
     * @throws Exception   【API 调用失败时抛出】
     */
    default String complete(String systemPrompt, String userPrompt, boolean jsonMode) throws Exception {
        return complete(systemPrompt, userPrompt);
    }

    /**
     * 【流式文本完成。通过 onChunk 回调逐步推送增量文本片段，实现打字机效果。
     * 默认实现回退到单次 complete() 调用——旧 provider 无需实现 SSE 也能正常工作，
     * 调用方代码保持正确（只是失去流式 UX 体验）。
     *
     * <p>实现要点：
     * <ul>
     *   <li>必须在流完全消费完毕或抛出异常后才返回</li>
     *   <li>返回完整累积文本，调用方无需自行拼接每个 chunk</li>
     *   <li>chunk 大小由实现决定（token 级 / 句子级 / 词级）</li>
     * </ul>
     * </p>
     *
     * <p>English: Streaming variant of {@link #complete}. The consumer is called once per incremental
     * text chunk in order; implementations decide chunk size (token / sentence / word).
     * Default falls back to a single-shot call, so older providers don't have to implement
     * SSE — callers stay correct (just lose the streaming UX for those backends).
     *
     * Must not return until the stream is fully consumed or an exception is thrown.
     * The accumulated content is returned so callers don't have to remember every chunk.</p>
     *
     * @param systemPrompt 【系统提示词】
     * @param userPrompt   【用户提示词】
     * @param onChunk      【每收到一个增量文本片段时的回调函数】
     * @return 【完整累积文本】
     * @throws Exception   【API 调用失败时抛出】
     */
    default String stream(String systemPrompt, String userPrompt, java.util.function.Consumer<String> onChunk) throws Exception {
        var full = complete(systemPrompt, userPrompt);
        if (full != null && !full.isEmpty()) onChunk.accept(full);
        return full == null ? "" : full;
    }

    /**
     * 【声明此 provider 是否支持真正的增量流式传输。
     * 用于能力门控——如果返回 false，调用方可能选择使用长轮询而非 SSE。
     * 默认返回 false，仅真正实现了 SSE 的 provider 需要覆写为 true。】
     *
     * <p>English: True when {@link #stream} actually streams (incremental). Used for capability gating.</p>
     */
    default boolean supportsStreaming() { return false; }
}
