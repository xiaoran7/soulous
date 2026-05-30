package com.soulous.ai.provider;

/**
 * 【占位用的模拟 LLM 提供商实现，永远处于"不可用"状态。
 *
 * <p>当系统中没有配置真实的 LLM API 密钥时，{@link com.soulous.ai.provider.LlmProvider} 的
 * 工厂方法会返回此实例。调用方通过 {@link #available()} 检测到不可用后，可降级到
 * 规则引擎或其它非 AI 逻辑。</p>
 *
 * <p>English: Placeholder provider that is never "available" — callers fall back to rule-based logic.</p>
 */
public final class MockProvider implements LlmProvider {

    /** 【提供商实例名称，默认为 "mock"，用于日志中标识当前使用的是模拟提供商】 */
    private final String name;

    /**
     * 【构造函数：创建模拟提供商实例。】
     *
     * @param name 【实例名称，null 时回退为 "mock"】
     */
    public MockProvider(String name) {
        this.name = name == null ? "mock" : name;
    }

    /** 【返回提供商实例名称】 */
    @Override public String name() { return name; }

    /** 【返回提供商类型标识 "mock"，用于按类型区分真实与模拟提供商】 */
    @Override public String type() { return "mock"; }

    /** 【返回空字符串，模拟提供商无实际模型】 */
    @Override public String model() { return ""; }

    /** 【始终返回 false，表示此提供商不可用，触发调用方降级逻辑】 */
    @Override public boolean available() { return false; }

    /**
     * 【模拟补全方法，直接返回空字符串。实际不会被调用（因 available() 为 false），
     * 仅作为接口契约的实现。】
     *
     * @param systemPrompt 【系统提示词（此处未使用）】
     * @param userPrompt   【用户提示词（此处未使用）】
     * @return 【始终返回空字符串 ""】
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return "";
    }
}
