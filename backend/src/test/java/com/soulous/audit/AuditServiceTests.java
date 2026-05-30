package com.soulous.audit;

import com.soulous.auth.RefreshTokenService;
import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies the unified audit log: record() lands a row, exceptions are
 * swallowed so audit failures never break the business op, and — critically —
 * the refresh-token replay branch's audit insert survives the surrounding
 * transaction rollback (the rollback is triggered by the UnauthorizedException
 * thrown after panic-revocation). Filter pathways are covered too.
 *
 * 【统一审计日志服务的集成测试，覆盖以下核心场景：
 *  1. record() 正确写入审计行（包含 action、actor、IP、User-Agent、details 等字段）
 *  2. 异常被吞噬，审计失败不会影响业务操作
 *  3. 刷新令牌重放分支的审计插入在事务回滚后仍然存活
 *    （回滚由 panic-revocation 后抛出的 UnauthorizedException 触发，
 *     审计服务使用 REQUIRES_NEW 传播确保独立事务）
 *  4. 登录失败记录尝试的用户名
 *  5. 按 action/actor/时间范围的过滤查询
 *  6. 禁用状态下服务为空操作】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class AuditServiceTests {
    @Autowired UserService users;
    @Autowired AuditService audit;
    @Autowired AuditLogRepository repo;
    @Autowired RefreshTokenService refreshTokens;

    /**
     * 【测试 record() 正确写入审计行：
     *  验证 action、actorUsername、actorRole、success、IP、User-Agent、
     *  details 字段和 createdAt 时间戳。】
     */
    @Test
    void recordWritesRowSuccessfully() {
        var user = registerFresh("rec");
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.5");
        req.addHeader("User-Agent", "JUnit/UA");

        audit.record(AuditAction.LOGIN_SUCCESS, user, null, null, req, true, "hello");

        var rows = repo.findAll().stream().filter(r -> AuditAction.LOGIN_SUCCESS.equals(r.action)
                && user.id.equals(r.actorUserId)).toList();
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.actorUsername).isEqualTo(user.username);
        assertThat(row.actorRole).isEqualTo(user.role.name());
        assertThat(row.success).isTrue();
        assertThat(row.ip).isEqualTo("203.0.113.5");
        assertThat(row.userAgent).isEqualTo("JUnit/UA");
        assertThat(row.details).isEqualTo("hello");
        assertThat(row.createdAt).isNotNull();
    }

    /**
     * 【测试审计服务吞噬异常：当仓库抛出 RuntimeException 时，
     *  record() 不应向外传播异常，确保审计失败不影响业务操作。】
     */
    @Test
    void recordSwallowsExceptions() {
        // Use a sibling instance where the repo throws — exception must not propagate.
        var throwingRepo = org.mockito.Mockito.mock(AuditLogRepository.class);
        when(throwingRepo.save(any(AuditLog.class))).thenThrow(new RuntimeException("boom"));
        var svc = new TestableAuditService(throwingRepo);
        // Should NOT throw:
        svc.record(AuditAction.LOGIN_SUCCESS, null, "ghost", null, null, null, false, "x");
    }

    /**
     * 【测试登录失败记录尝试的用户名。
     *  验证 LOGIN_FAILED 审计行：actorUserId 为 null（无有效用户）、
     *  success=false、actorUsername 为尝试登录的用户名。】
     */
    @Test
    void loginFailedRecordsAttemptedUsername() {
        var user = registerFresh("loginfail");
        try {
            users.login(new com.soulous.auth.LoginRequest(user.username, "wrong-password-zzz"));
        } catch (UnauthorizedException ignored) {
        }
        // The controller path is what records LOGIN_FAILED; here we call it directly via audit
        // to assert the row shape (the controller-level integration is exercised by the auth flow tests).
        audit.record(AuditAction.LOGIN_FAILED, null, user.username, null, null, null, false, "bad password");

        var rows = repo.findAll().stream()
                .filter(r -> AuditAction.LOGIN_FAILED.equals(r.action) && user.username.equals(r.actorUsername))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).actorUserId).isNull();
        assertThat(rows.get(0).success).isFalse();
    }

    /**
     * 【测试刷新令牌重放审计在事务回滚后仍然存活。
     *  端到端流程：签发令牌 → 轮换（旧令牌撤销）→ 再次轮换已撤销令牌
     *  （触发 UnauthorizedException 回滚）→ 验证 REFRESH_TOKEN_REPLAYED 审计行存在。
     *  审计服务使用 REQUIRES_NEW 传播，确保审计插入不受外围事务回滚影响。】
     */
    @Test
    void replayedRefreshTokenWritesAuditEvenAfterRollback() {
        // End-to-end: rotating an already-revoked token throws UnauthorizedException, which
        // rolls back the rotate() tx. The audit row MUST still be there because the audit
        // service uses REQUIRES_NEW for this path.
        var user = registerFresh("replay");
        var first = refreshTokens.issue(user, "ua", "ip");
        refreshTokens.rotate(first.rawToken(), "ua", "ip"); // first now revoked

        assertThatThrownBy(() -> refreshTokens.rotate(first.rawToken(), "ua", "ip"))
                .isInstanceOf(UnauthorizedException.class);

        var rows = repo.findAll().stream()
                .filter(r -> AuditAction.REFRESH_TOKEN_REPLAYED.equals(r.action)
                        && user.id.equals(r.actorUserId))
                .toList();
        assertThat(rows).as("REFRESH_TOKEN_REPLAYED audit row must survive the rolled-back tx").hasSize(1);
        assertThat(rows.get(0).success).isFalse();
        assertThat(rows.get(0).targetType).isEqualTo("USER");
    }

    /**
     * 【测试按 action 和 actor 过滤审计日志。
     *  验证：按 PASSWORD_CHANGED 过滤只返回该类型记录，
     *  按 alice 过滤只返回 alice 的记录，
     *  按 action+actor 组合过滤返回交集结果。】
     */
    @Test
    void filterByActionAndActorReturnsCorrectSubset() {
        var alice = registerFresh("alice");
        var bob = registerFresh("bob");
        audit.record(AuditAction.LOGIN_SUCCESS, alice, null, null, null, true, null);
        audit.record(AuditAction.LOGIN_SUCCESS, bob, null, null, null, true, null);
        audit.record(AuditAction.PASSWORD_CHANGED, alice, null, null, null, true, null);

        var byAction = repo.search(AuditAction.PASSWORD_CHANGED, null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(byAction.getContent()).allMatch(r -> AuditAction.PASSWORD_CHANGED.equals(r.action));
        assertThat(byAction.getContent()).anyMatch(r -> alice.id.equals(r.actorUserId));

        var byActor = repo.search(null, alice.id, null, null,
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(byActor.getContent()).allMatch(r -> alice.id.equals(r.actorUserId));
        assertThat(byActor.getContent()).anyMatch(r -> AuditAction.PASSWORD_CHANGED.equals(r.action));
        assertThat(byActor.getContent()).anyMatch(r -> AuditAction.LOGIN_SUCCESS.equals(r.action));

        var combined = repo.search(AuditAction.LOGIN_SUCCESS, bob.id, null, null,
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(combined.getContent()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(combined.getContent())
                .allMatch(r -> AuditAction.LOGIN_SUCCESS.equals(r.action) && bob.id.equals(r.actorUserId));
    }

    /**
     * 【测试按时间范围过滤审计日志。
     *  未来时间点查询应返回空结果，
     *  过去时间点查询应包含匹配记录。】
     */
    @Test
    void filterByTimeRangeExcludesOutsiders() {
        var user = registerFresh("timefilter");
        audit.record(AuditAction.LOGIN_SUCCESS, user, null, null, null, true, "marker-A");

        var future = LocalDateTime.now().plusYears(1);
        var page = repo.search(AuditAction.LOGIN_SUCCESS, user.id, future, null,
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(page.getContent()).isEmpty();

        var past = LocalDateTime.now().minusYears(1);
        var page2 = repo.search(AuditAction.LOGIN_SUCCESS, user.id, past, LocalDateTime.now().plusMinutes(5),
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(page2.getContent()).isNotEmpty();
    }

    /**
     * 【测试审计服务禁用状态下为空操作。
     *  enabled=false 时，record() 和 recordInNewTransaction() 均不写入数据库，
     *  记录数不变。】
     */
    @Test
    void disabledServiceIsNoOp() {
        var user = registerFresh("disabled");
        // Build a separate instance with enabled=false and a real repo — record() must NOT save.
        var noop = new AuditService(repo, false) {};
        long before = repo.count();
        noop.record(AuditAction.LOGIN_SUCCESS, user, null, null, null, true, null);
        noop.recordInNewTransaction(AuditAction.LOGIN_SUCCESS, user, null, null, null, null, true, null);
        assertThat(repo.count()).isEqualTo(before);
    }

    /**
     * 【辅助方法：创建唯一用户名的测试用户】
     */
    private UserAccount registerFresh(String prefix) {
        var unique = prefix + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", prefix, unique + "@example.com"));
        return users.byToken(auth.token());
    }

    /**
     * Test-only subclass that lets us inject a throwing repo without going through Spring.
     * Avoids the "multiple constructors" confusion that bit a prior phase — we keep
     * AuditService's single @Autowired ctor untouched and just construct it directly here.
     *
     * 【测试专用子类：允许注入抛异常的仓库而无需通过 Spring。
     *  避免"多个构造函数"的混淆，保持 AuditService 的单个 @Autowired 构造函数不变，
     *  直接在此处构造实例。】
     */
    static class TestableAuditService extends AuditService {
        TestableAuditService(AuditLogRepository repo) {
            super(repo, true);
        }
    }
}
