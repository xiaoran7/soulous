package com.soulous.auth;

import com.soulous.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the refresh-token lifecycle: issue, resolve, rotate, revoke,
 * replay-detection, and the cascading revocation that fires when a revoked
 * token is reused (the classic "rotated-token theft" tripwire).
 *
 * 【RefreshTokenService 的集成测试，覆盖刷新令牌完整生命周期：
 *  1. 签发（issue）：生成原始令牌和 SHA-256 哈希存储
 *  2. 解析（resolve）：通过原始令牌解析用户 ID
 *  3. 轮换（rotate）：撤销旧令牌、签发新令牌
 *  4. 撤销（revoke）：撤销用户所有令牌
 *  5. 重放检测：已撤销令牌再次使用时触发级联撤销（防盗机制）
 *  6. 过期令牌和空/未知令牌的拒绝
 *  使用 H2 内存数据库。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refresh-token-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class RefreshTokenServiceTests {
    @Autowired UserService users;
    @Autowired RefreshTokenService refreshTokens;
    @Autowired RefreshTokenRepository repo;

    /**
     * 【测试签发和解析的往返一致性。
     *  验证：原始令牌非空，存储的哈希不等于原始令牌且长度为 64（SHA-256 hex），
     *  解析后用户 ID 正确。】
     */
    @Test
    void issueAndResolveRoundTrip() {
        var user = registerFresh("issue");
        var issued = refreshTokens.issue(user, "JUnit/UA", "127.0.0.1");

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.entity().tokenHash)
                .isNotEqualTo(issued.rawToken())
                .hasSize(64); // SHA-256 hex

        var resolved = refreshTokens.resolve(issued.rawToken());
        assertThat(resolved.id).isEqualTo(user.id);
    }

    /**
     * 【测试轮换：旧令牌撤销、新令牌签发。
     *  验证：新旧令牌不同，旧令牌 resolve 失败（UnauthorizedException），
     *  新令牌 resolve 成功且用户 ID 正确。】
     */
    @Test
    void rotateRevokesOldIssuesNew() {
        var user = registerFresh("rotate");
        var first = refreshTokens.issue(user, "ua", "ip");

        var second = refreshTokens.rotate(first.rawToken(), "ua", "ip");

        assertThat(second.rawToken()).isNotEqualTo(first.rawToken());
        // old one is revoked → resolve fails
        // 【旧令牌已撤销 → resolve 失败】
        assertThatThrownBy(() -> refreshTokens.resolve(first.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
        // new one works
        // 【新令牌正常工作】
        assertThat(refreshTokens.resolve(second.rawToken()).id).isEqualTo(user.id);
    }

    /**
     * 【测试重放检测的级联撤销（防盗机制）。
     *  流程：签发 2 个令牌 → 轮换第 1 个 → 再次使用已撤销的第 1 个令牌
     *  （触发 "replayed" 异常）→ 验证第 2 个"合法"令牌也被级联撤销。
     *  这是经典的"轮换令牌被盗"陷阱：宁可误杀也不能放过。】
     */
    @Test
    void reusingRevokedTokenRevokesAllUserSessions() {
        // Theft tripwire: if a token that's already been rotated comes back, treat it as
        // a compromised cookie and revoke EVERY refresh row for that user.
        // 【防盗机制：已轮换的令牌再次出现时，视为 cookie 被盗，撤销该用户所有刷新令牌】
        var user = registerFresh("replay");
        var first = refreshTokens.issue(user, "ua", "ip");
        var second = refreshTokens.issue(user, "ua", "ip"); // legitimate second session
        // 【第二个合法会话】
        refreshTokens.rotate(first.rawToken(), "ua", "ip"); // first now revoked
        // 【第一个现在已撤销】

        assertThatThrownBy(() -> refreshTokens.rotate(first.rawToken(), "ua", "ip"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("replayed");

        // The "legitimate" second session also gets nuked — better safe than sorry.
        // 【"合法"的第二个会话也被一并撤销 — 宁可误杀也不能放过】
        assertThatThrownBy(() -> refreshTokens.resolve(second.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    /**
     * 【测试撤销用户所有令牌。
     *  签发 2 个令牌后全部撤销，验证返回值为 2，
     *  两个令牌均无法解析。】
     */
    @Test
    void revokeAllForUserKillsEverything() {
        var user = registerFresh("revokeall");
        var a = refreshTokens.issue(user, "ua", "ip");
        var b = refreshTokens.issue(user, "ua", "ip");

        int n = refreshTokens.revokeAllForUser(user);
        assertThat(n).isEqualTo(2);

        assertThatThrownBy(() -> refreshTokens.resolve(a.rawToken())).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> refreshTokens.resolve(b.rawToken())).isInstanceOf(UnauthorizedException.class);
    }

    /**
     * 【测试过期令牌被拒绝。
     *  通过手动修改 expiresAt 为过去时间模拟过期，
     *  resolve 应抛出 UnauthorizedException。】
     */
    @Test
    void expiredTokenIsRejected() {
        var user = registerFresh("expired");
        var issued = refreshTokens.issue(user, "ua", "ip");
        // simulate expiry by editing the entity
        // 【通过编辑实体模拟过期】
        var row = repo.findByTokenHash(RefreshTokenService.hash(issued.rawToken())).orElseThrow();
        row.expiresAt = LocalDateTime.now().minusSeconds(10);
        repo.save(row);

        assertThatThrownBy(() -> refreshTokens.resolve(issued.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    /**
     * 【测试空或未知令牌被拒绝。
     *  null、空字符串、不存在的令牌均应抛出 UnauthorizedException。】
     */
    @Test
    void missingOrUnknownTokenRejected() {
        assertThatThrownBy(() -> refreshTokens.resolve(null)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> refreshTokens.resolve("")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> refreshTokens.resolve("does-not-exist")).isInstanceOf(UnauthorizedException.class);
    }

    /**
     * 【辅助方法：创建唯一用户名的测试用户】
     */
    private UserAccount registerFresh(String prefix) {
        var unique = prefix + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", prefix, unique + "@example.com"));
        return users.byToken(auth.token());
    }
}
