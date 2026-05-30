package com.soulous;

import com.soulous.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【生产环境配置测试类：验证 prod profile 下的安全启动约束，
 * 包括默认 JWT secret 拒绝启动、强 secret 下正常启动且不预置默认用户（demo/admin）。
 * 通过手动启动 SpringApplication 并设置 prod profile 模拟生产环境行为。】
 */
class ProdProfileTests {

    /**
     * 【辅助方法：以 prod profile 启动完整 Spring 应用上下文，
     * 用于测试生产环境配置的启动行为。】
     */
    private static ConfigurableApplicationContext start(String... args) {
        var app = new SpringApplication(SoulousApplication.class);
        app.setAdditionalProfiles("prod");
        return app.run(args);
    }

    /**
     * 【测试场景：prod profile 下使用默认开发 JWT secret 启动时，
     * 应抛出 IllegalStateException 或 BeanCreationException，
     * 错误信息中包含"development JWT secret"，防止生产环境误用不安全的密钥。】
     */
    @Test
    void prodProfileRefusesToStartWithDefaultJwtSecret() {
        assertThatThrownBy(() -> {
            try (var ctx = start(
                    "--spring.datasource.url=jdbc:h2:mem:prod-bad-secret;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "--soulous.upload-dir=target/test-uploads",
                    "--soulous.cors-origin=https://example.com",
                    "--server.port=0")) {
                // should not get here
            }
        }).isInstanceOfAny(IllegalStateException.class, BeanCreationException.class)
                .hasMessageContaining("development JWT secret");
    }

    /**
     * 【测试场景：prod profile 下使用足够强度的 JWT secret 启动时，
     * 应用应成功启动，且数据库中不应存在 demo 或 admin 默认用户，
     * 确保生产环境不会预置可被猜测的初始账户。】
     */
    @Test
    void prodProfileStartsWithStrongSecretAndDoesNotSeedDefaultUsers() {
        try (var ctx = start(
                "--spring.datasource.url=jdbc:h2:mem:prod-ok;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "--soulous.upload-dir=target/test-uploads",
                "--soulous.cors-origin=https://example.com,https://other.example.com",
                "--soulous.jwt.secret=production-grade-secret-with-enough-bytes-to-satisfy-hmac-sha256",
                "--server.port=0")) {

            var repo = ctx.getBean(UserRepository.class);
            assertThat(repo.findByUsername("demo")).isEmpty();
            assertThat(repo.findByUsername("admin")).isEmpty();
        }
    }
}
