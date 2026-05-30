package com.soulous;

import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserService;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【JWT 撤销测试类：验证用户认证令牌的生命周期管理，包括密码修改后旧令牌失效、
 * 错误当前密码拒绝修改、弱新密码拒绝、撤销全部令牌后所有已有令牌失效、
 * 以及刷新令牌后新旧令牌均可正常使用。确保令牌安全性与会话控制的正确性。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:soulous-revocation;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class JwtRevocationTests {
    @Autowired UserService users;

    /**
     * 【测试场景：用户修改密码后，旧令牌应立即失效（抛出 UnauthorizedException），
     * 使用新令牌应能正常获取用户信息。】
     */
    @Test
    void passwordChangeRevokesOldToken() {
        var unique = "pwchange" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", "用户", unique + "@example.com"));
        var oldToken = auth.token();

        var user = users.byToken(oldToken);
        var refreshed = users.changePassword(user, "Passw0rd!", "NewPassw0rd!");

        assertThatThrownBy(() -> users.byToken(oldToken)).isInstanceOf(UnauthorizedException.class);
        assertThat(users.byToken(refreshed.token()).username).isEqualTo(unique);
    }

    /**
     * 【测试场景：使用错误的当前密码尝试修改密码时应抛出 UnauthorizedException，
     * 原有令牌应继续有效不受影响。】
     */
    @Test
    void wrongCurrentPasswordIsRejected() {
        var unique = "wrongpw" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", "用户", unique + "@example.com"));
        var user = users.byToken(auth.token());

        assertThatThrownBy(() -> users.changePassword(user, "WrongPw1!", "NewPassw0rd!"))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(users.byToken(auth.token()).username).isEqualTo(unique);
    }

    /**
     * 【测试场景：新密码不符合强度要求时应抛出 BadRequestException，
     * 原有令牌应继续有效不受影响。】
     */
    @Test
    void weakNewPasswordIsRejected() {
        var unique = "weakpw" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", "用户", unique + "@example.com"));
        var user = users.byToken(auth.token());

        assertThatThrownBy(() -> users.changePassword(user, "Passw0rd!", "short"))
                .isInstanceOf(BadRequestException.class);
        assertThat(users.byToken(auth.token()).username).isEqualTo(unique);
    }

    /**
     * 【测试场景：调用 revokeAllTokens 后，该用户的所有已有令牌（包括原始令牌和刷新令牌）
     * 均应失效，使用任一令牌访问均应抛出 UnauthorizedException。】
     */
    @Test
    void revokeAllInvalidatesAllExistingTokens() {
        var unique = "revoke" + System.nanoTime();
        var auth1 = users.register(new RegisterRequest(unique, "Passw0rd!", "用户", unique + "@example.com"));
        var user = users.byToken(auth1.token());
        var auth2 = users.refresh(user);

        users.revokeAllTokens(user);

        assertThatThrownBy(() -> users.byToken(auth1.token())).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> users.byToken(auth2.token())).isInstanceOf(UnauthorizedException.class);
    }

    /**
     * 【测试场景：refresh 操作应颁发新的可用令牌，新令牌可正常获取用户信息，
     * 且原令牌也应继续有效（refresh 不会使旧令牌失效）。】
     */
    @Test
    void refreshIssuesUsableToken() {
        var unique = "refresh" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", "用户", unique + "@example.com"));
        var user = users.byToken(auth.token());

        var refreshed = users.refresh(user);

        assertThat(refreshed.token()).isNotBlank();
        assertThat(users.byToken(refreshed.token()).id).isEqualTo(user.id);
        assertThat(users.byToken(auth.token()).id).isEqualTo(user.id);
    }
}
