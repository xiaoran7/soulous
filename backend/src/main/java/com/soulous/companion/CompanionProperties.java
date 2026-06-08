package com.soulous.companion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 【陪伴宠物配置：绑定 application.yml 中 soulous.companion.* 前缀。
 * 宠物聊天的「大脑」跑在独立的 Anima agent 服务里（带记忆/人格/工具），Soulous 通过 HTTP 调它。】
 *
 * <p>English: Binds {@code soulous.companion.*}. The companion pet's brain runs in the standalone
 * Anima agent service (memory / persona / tools); Soulous calls it over HTTP. Fully decoupled —
 * Anima knows nothing about Soulous.</p>
 */
@Component
@ConfigurationProperties(prefix = "soulous.companion")
public class CompanionProperties {
    /** 【总开关。关闭后宠物聊天返回兜底文案，不调用 Anima】 */
    private boolean enabled = true;

    /** 【Anima 服务基础地址。本地默认 8090；生产走 VPS 内网地址】 */
    private String baseUrl = "http://localhost:8090";

    /** 【使用的人格 ID，对应 Anima personas/*.yaml 里的 id】 */
    private String personaId = "soulous_pet";

    /** 【调用 Anima 的超时（秒）。DeepSeek + 工具多步可能较慢】 */
    private int timeoutSeconds = 120;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getPersonaId() { return personaId; }
    public void setPersonaId(String personaId) { this.personaId = personaId; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
