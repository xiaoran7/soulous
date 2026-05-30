package com.soulous;

import com.soulous.auth.LoginRequest;
import com.soulous.auth.UserRole;
import com.soulous.auth.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the Actuator + Micrometer wiring. We assert on the export path:
 *  - public endpoints stay public,
 *  - sensitive endpoints require ROLE_ADMIN,
 *  - the Prometheus scrape body lists our custom metric families (HELP/TYPE lines are
 *    enough — values are exercised by their owning services in other tests).
 *
 * The custom HealthIndicators (storage + LLM) are off by default and intentionally
 * NOT exercised here — flipping them on would require either real provider creds or
 * a heavyweight mock, both out of scope for this exposure test.
 *
 * 【Actuator + Micrometer 监控指标暴露的冒烟测试。
 *  验证指标导出路径：
 *  1. /actuator/health 公开端点无需认证即可访问
 *  2. /actuator/prometheus 敏感端点需要 ROLE_ADMIN 权限
 *  3. Prometheus 抓取体包含自定义指标族（soulous_llm_calls_total、
 *     soulous_rate_limit_blocked_total、soulous_notification_pushed_total）
 *  自定义 HealthIndicators（存储+LLM）默认关闭，不在此测试范围内。】
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:metrics-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "soulous.upload-dir=target/test-uploads"
        }
)
class MetricsExposureTests {

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired UserService userService;

    /**
     * 【测试健康检查端点公开可访问，返回 200 且 body 包含 "status"。】
     */
    @Test
    void healthEndpointPublic() {
        var resp = http.getForEntity(url("/actuator/health"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("status");
    }

    /**
     * 【测试 Prometheus 端点未认证时返回 401。】
     */
    @Test
    void prometheusEndpointRequiresAdmin() {
        var resp = http.getForEntity(url("/actuator/prometheus"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * 【测试管理员认证后可访问 Prometheus 端点，返回 200。
     *  验证抓取体包含三个自定义指标族（Micrometer 将点号转为下划线）。】
     */
    @Test
    void prometheusEndpointAdminCanRead() {
        var username = "metrics-admin-" + System.nanoTime();
        userService.ensureUser(username, "Passw0rd!", "M", UserRole.ADMIN);
        var token = userService.login(new LoginRequest(username, "Passw0rd!")).token();

        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(java.util.List.of(MediaType.ALL));
        var resp = http.exchange(url("/actuator/prometheus"),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = resp.getBody();
        assertThat(body).isNotNull();
        // Micrometer translates dots to underscores in Prometheus exposition.
        // 【Micrometer 在 Prometheus 暴露格式中将点号转为下划线】
        assertThat(body).contains("soulous_llm_calls_total");
        assertThat(body).contains("soulous_rate_limit_blocked_total");
        assertThat(body).contains("soulous_notification_pushed_total");
    }

    /**
     * 【辅助方法：拼接 localhost + 端口 + 路径的完整 URL】
     */
    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
