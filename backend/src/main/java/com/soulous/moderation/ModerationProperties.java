package com.soulous.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 【内容审核配置属性】
 * 绑定 {@code soulous.moderation.*} 前缀的配置项。
 * 支持全局启用/禁用审核、选择用于审核调用的 LLM 提供商，以及调整风险阈值。
 *
 * <p>English: Binds {@code soulous.moderation.*} configuration.</p>
 *
 * <p>English: Supports enabling/disabling moderation globally, choosing which LLM provider
 * to use for moderation calls, and tuning risk thresholds.</p>
 */
@Component
@ConfigurationProperties(prefix = "soulous.moderation")
public class ModerationProperties {
    /**
     * 【总开关】默认关闭，通过 {@code SOULOUS_MODERATION_ENABLED=true} 环境变量启用。
     * English: Master switch. Default OFF — opt-in via {@code SOULOUS_MODERATION_ENABLED=true}.
     */
    private boolean enabled = false;

    /**
     * 【是否审核输出】是否对 LLM 输出进行审核（除了用户输入之外）。默认开启。
     * English: Whether to moderate LLM output (in addition to user input).
     */
    private boolean moderateOutput = true;

    /**
     * 【审核专用 LLM 提供商】指定用于审核调用的已注册 LLM 提供商。
     * 为空时使用默认提供商。建议指向廉价/快速的模型（如 "ollama"）以最小化延迟和成本。
     *
     * <p>English: Which registered LLM provider to use for moderation calls.
     * Blank = use the default provider. Tip: point this at a cheap/fast model
     * (e.g. "ollama") to minimise latency and cost.</p>
     */
    private String provider = "";

    /**
     * 【阻止阈值】风险分数 ≥ 此值时判定为 BLOCK（阻止）。默认 80。
     * English: Risk score ≥ this value → BLOCK.
     */
    private int blockThreshold = 80;

    /**
     * 【标记阈值】风险分数 ≥ 此值（且 < blockThreshold）时判定为 FLAG（标记）。默认 50。
     * English: Risk score ≥ this value (and < blockThreshold) → FLAG.
     */
    private int flagThreshold = 50;

    /**
     * 【上下文窗口大小】作为上下文包含在审核提示词中的最近对话轮数。默认 6。
     * English: Max recent turns to include as context in the moderation prompt.
     */
    private int contextWindow = 6;

    /**
     * 【日志开关】是否将审核日志持久化到数据库。默认开启。
     * English: Whether to persist moderation logs to the database.
     */
    private boolean logEnabled = true;

    /**
     * 【快速阻止正则模式】基于规则的快速路径模式（正则表达式）。
     * 匹配的输入将被立即阻止，无需调用 LLM。
     * English: Rule-based fast-path patterns (regex). Matched input is instantly blocked without LLM.
     */
    private String[] fastBlockPatterns = {};

    // ----- getters / setters -----

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isModerateOutput() { return moderateOutput; }
    public void setModerateOutput(boolean moderateOutput) { this.moderateOutput = moderateOutput; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public int getBlockThreshold() { return blockThreshold; }
    public void setBlockThreshold(int blockThreshold) { this.blockThreshold = blockThreshold; }

    public int getFlagThreshold() { return flagThreshold; }
    public void setFlagThreshold(int flagThreshold) { this.flagThreshold = flagThreshold; }

    public int getContextWindow() { return contextWindow; }
    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }

    public boolean isLogEnabled() { return logEnabled; }
    public void setLogEnabled(boolean logEnabled) { this.logEnabled = logEnabled; }

    public String[] getFastBlockPatterns() { return fastBlockPatterns; }
    public void setFastBlockPatterns(String[] fastBlockPatterns) { this.fastBlockPatterns = fastBlockPatterns; }
}
