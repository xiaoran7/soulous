package com.soulous.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 【agent-service 边车配置（soulous.agent.*）】
 *
 * <p>enabled=true 时，AI 拆解对话 / 任务审核 / 每日复盘三条链路优先走 agent-service
 * （Python LangGraph 认知服务）；agent 不可用时各链路自动回退原有本地 LlmService / 规则路径。</p>
 */
@Component
@ConfigurationProperties(prefix = "soulous.agent")
public class AgentProperties {
    /** 【总开关：false 时所有链路保持原有本地行为，agent 代码零介入】 */
    private boolean enabled = false;
    /** 【agent-service 基地址（内网），如 http://127.0.0.1:8100】 */
    private String baseUrl = "http://127.0.0.1:8100";
    /** 【Spring ⇄ agent 共享密钥（X-Service-Token）】 */
    private String token = "";
    /** 【非流式调用超时（秒）：审核/复盘单发调用】 */
    private int timeoutSeconds = 30;
    /** 【流式调用超时（秒）：聊天 SSE 全程上限】 */
    private int streamTimeoutSeconds = 300;
    /** 【启动时把存量 memory_embedding 语料回灌进 agent 向量库（一次性迁移用）】 */
    private boolean backfill = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getStreamTimeoutSeconds() { return streamTimeoutSeconds; }
    public void setStreamTimeoutSeconds(int streamTimeoutSeconds) { this.streamTimeoutSeconds = streamTimeoutSeconds; }
    public boolean isBackfill() { return backfill; }
    public void setBackfill(boolean backfill) { this.backfill = backfill; }
}
