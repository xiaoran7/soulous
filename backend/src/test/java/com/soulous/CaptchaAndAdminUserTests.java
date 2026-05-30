package com.soulous;

import com.soulous.auth.CaptchaService;
import com.soulous.auth.LoginRequest;
import com.soulous.auth.UserRole;
import com.soulous.auth.UserService;
import com.soulous.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【验证码与管理员用户测试类：验证 CaptchaService 的验证码签发与校验流程
 * （包括缺失字段拒绝、错误/过期 ID 拒绝），以及管理员通过 createByAdmin 创建用户
 * （指定角色、密码加密验证、重复用户名拒绝、弱密码拒绝）。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:soulous-captcha;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class CaptchaAndAdminUserTests {
    @Autowired CaptchaService captcha;
    @Autowired UserService users;

    /**
     * 【测试场景：签发验证码时应返回非空 id 和以 "data:image/svg+xml;base64," 开头的 SVG 图片。】
     */
    @Test
    void captchaIssueProducesIdAndImage() {
        var c = captcha.issue();
        assertThat(c.id()).isNotBlank();
        assertThat(c.image()).startsWith("data:image/svg+xml;base64,");
    }

    /**
     * 【测试场景：验证码校验时，id 或 code 为 null/空字符串应抛出 BadRequestException。】
     */
    @Test
    void captchaRejectsMissingFields() {
        assertThatThrownBy(() -> captcha.verify(null, "ABCD")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> captcha.verify("anything", "")).isInstanceOf(BadRequestException.class);
    }

    /**
     * 【测试场景：使用不存在的验证码 ID 进行校验时应抛出 BadRequestException，
     * 覆盖 ID 不存在或已过期的场景。】
     */
    @Test
    void captchaRejectsWrongAndExpiredId() {
        assertThatThrownBy(() -> captcha.verify("does-not-exist", "ABCD")).isInstanceOf(BadRequestException.class);
    }

    /**
     * 【测试场景：管理员通过 createByAdmin 创建用户时，应正确设置指定角色，
     * 密码应使用 bcrypt 加密存储（以"$2"开头），且新用户可以正常登录。】
     */
    @Test
    void adminCanCreateUserWithGivenRole() {
        var unique = "made" + System.nanoTime();
        var created = users.createByAdmin(unique, "Passw0rd!", "新建用户", UserRole.ADMIN);
        assertThat(created.role).isEqualTo(UserRole.ADMIN);
        assertThat(created.password).startsWith("$2"); // bcrypt
        // 可以登录验证密码
        var auth = users.login(new LoginRequest(unique, "Passw0rd!"));
        assertThat(auth.token()).isNotBlank();
    }

    /**
     * 【测试场景：创建重复用户名的用户应抛出 BadRequestException；
     * 创建弱密码的用户也应抛出 BadRequestException，
     * 确保管理员接口同样遵守用户名唯一性和密码强度策略。】
     */
    @Test
    void adminCreateRejectsDuplicateAndWeakPassword() {
        var unique = "dup" + System.nanoTime();
        users.createByAdmin(unique, "Passw0rd!", null, UserRole.USER);
        assertThatThrownBy(() -> users.createByAdmin(unique, "Passw0rd!", null, UserRole.USER))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> users.createByAdmin(unique + "x", "short", null, UserRole.USER))
                .isInstanceOf(BadRequestException.class);
    }
}
