package com.soulous.auth;

import com.soulous.audit.AuditAction;
import com.soulous.audit.AuditService;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.UnauthorizedException;
import com.soulous.common.ratelimit.RateLimit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 移动端原生客户端专用的认证控制器。
 *
 * <p>与 Web 端 {@link AuthController} 的主要区别：
 * <ul>
 *   <li>刷新令牌通过响应体返回，而非设置 Cookie。原生应用将令牌存储在平台安全存储中
 *       （iOS Keychain / Android Keystore），并在刷新时显式发送，无需 Cookie 机制。</li>
 *   <li>不使用验证码（CAPTCHA）；依赖已有的 {@link RateLimit} 限流桶以及未来的设备证明
 *       （Play Integrity、App Attest）作为反滥用层。</li>
 *   <li>登录/注册与 Web 端共享相同的限流桶名称，防止攻击者通过某一渠道绕过频率限制进行暴力破解。</li>
 * </ul>
 *
 * <p>底层服务（{@link UserService}、{@link RefreshTokenService}、{@link JwtService}）
 * 完全复用，本控制器仅负责适配移动端的请求/响应格式。
 *
 * <p>Auth endpoints tailored for native mobile clients.
 *
 * <p>Differences vs the web {@link AuthController}:
 * <ul>
 *   <li>Refresh tokens are returned in the response body, not set as cookies. Native apps
 *       store them in platform secure storage (Keychain / Keystore) and send them
 *       explicitly on refresh — no cookie jar required.</li>
 *   <li>CAPTCHA is omitted; the existing {@link RateLimit} bucket plus future device
 *       attestation (Play Integrity, App Attest) are the anti-abuse layer here.</li>
 *   <li>Login / register share the same anti-abuse bucket names as web so we can't
 *       brute-force from one channel while the other is rate-capped.</li>
 * </ul>
 *
 * <p>All underlying services ({@link UserService}, {@link RefreshTokenService},
 * {@link JwtService}) are reused unchanged — this controller only adapts the
 * request/response shape.
 */
@RestController
@RequestMapping("/api/auth/mobile")
public class AuthMobileController {
    /** 用户服务，负责登录、注册、令牌解析等核心业务逻辑 */
    private final UserService users;
    /** 刷新令牌服务，负责令牌的颁发、轮换、撤销 */
    private final RefreshTokenService refreshTokens;
    /** 审计服务，用于记录安全相关操作日志 */
    private final AuditService audit;
    /** 访问令牌的有效期（秒），从配置项 soulous.jwt.access-ttl-seconds 读取，默认 3600 秒 */
    private final long accessTtlSeconds;

    AuthMobileController(UserService users,
                         RefreshTokenService refreshTokens,
                         AuditService audit,
                         @Value("${soulous.jwt.access-ttl-seconds:3600}") long accessTtlSeconds) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.accessTtlSeconds = accessTtlSeconds;
    }

    /**
     * 移动端登录接口。
     * 验证用户名密码后颁发访问令牌和刷新令牌，刷新令牌通过响应体返回。
     * 登录失败时记录审计日志（包含失败原因），便于安全审计和异常检测。
     *
     * @param body    【移动端登录请求体，包含用户名和密码】
     * @param request 【HTTP 请求，用于获取 User-Agent 和客户端 IP】
     * @return 【包含 accessToken、refreshToken、过期时间和用户信息的响应体】
     */
    @PostMapping("/login")
    @RateLimit(name = "auth-login", capacity = 5, refillTokens = 5, refillPeriod = 1)
    MobileAuthResponse login(@Valid @RequestBody MobileLoginRequest body,
                             HttpServletRequest request) {
        AuthResponse auth;
        try {
            auth = users.login(new LoginRequest(body.username(), body.password()));
        } catch (UnauthorizedException ex) {
            // 【记录登录失败的审计日志，包含失败原因，便于后续安全分析】
            audit.record(AuditAction.LOGIN_FAILED, null, body.username(), null, null,
                    request, false, "mobile login failed: " + ex.getMessage());
            throw ex;
        }
        // 【通过访问令牌解析出用户实体，用于颁发刷新令牌和记录审计日志】
        var actor = users.byToken(auth.token());
        // 【颁发一次性刷新令牌，记录 User-Agent 和 IP 用于会话管理】
        var issued = refreshTokens.issue(actor, request.getHeader("User-Agent"), clientIp(request));
        audit.record(AuditAction.LOGIN_SUCCESS, actor, null, null, request, true, "channel=mobile");
        return new MobileAuthResponse(auth.token(), issued.rawToken(), accessTtlSeconds, auth.user());
    }

    /**
     * 移动端注册接口。
     * 先校验两次密码是否一致，然后调用 UserService 创建用户，
     * 最后颁发访问令牌和刷新令牌。注册成功后用户自动登录。
     *
     * @param body    【移动端注册请求体，包含用户名、密码、确认密码和可选昵称】
     * @param request 【HTTP 请求，用于获取 User-Agent 和客户端 IP】
     * @return 【包含 accessToken、refreshToken、过期时间和用户信息的响应体】
     * @throws BadRequestException 【当两次密码不一致时抛出】
     */
    @PostMapping("/register")
    @RateLimit(name = "auth-register", capacity = 5, refillTokens = 5, refillPeriod = 1)
    MobileAuthResponse register(@Valid @RequestBody MobileRegisterRequest body,
                                HttpServletRequest request) {
        if (!body.password().equals(body.confirmPassword())) {
            throw new BadRequestException("两次输入的密码不一致");
        }
        // 【注册时 nickname 和 email 为可选，email 传 null】
        var auth = users.register(new RegisterRequest(
                body.username(), body.password(), body.nickname(), null));
        var actor = users.byToken(auth.token());
        var issued = refreshTokens.issue(actor, request.getHeader("User-Agent"), clientIp(request));
        return new MobileAuthResponse(auth.token(), issued.rawToken(), accessTtlSeconds, auth.user());
    }

    /**
     * 轮换刷新令牌并返回新的访问/刷新令牌对。
     * 旧的刷新令牌是一次性的；如果重复使用，{@link RefreshTokenService#rotate} 会触发
     * 重放检测机制，撤销该用户的所有令牌（安全防护：防止被盗令牌同时被攻击者和受害者使用）。
     *
     * <p>Rotate the supplied refresh token and return a fresh access/refresh pair.
     * The old refresh token is single-use; reusing it triggers replay-detection
     * inside {@link RefreshTokenService#rotate} and revokes ALL the user's tokens.</p>
     *
     * @param body    【包含待轮换的旧刷新令牌】
     * @param request 【HTTP 请求，用于获取 User-Agent 和客户端 IP】
     * @return 【新的 accessToken、refreshToken、过期时间和用户信息】
     * @throws UnauthorizedException 【当刷新令牌无效、过期或已被重放使用时抛出】
     */
    @PostMapping("/refresh")
    @RateLimit(name = "auth-refresh", capacity = 30, refillTokens = 30, refillPeriod = 1)
    MobileAuthResponse refresh(@Valid @RequestBody MobileRefreshRequest body,
                               HttpServletRequest request) {
        var issued = refreshTokens.rotate(
                body.refreshToken(), request.getHeader("User-Agent"), clientIp(request));
        var auth = users.refresh(issued.entity().user);
        return new MobileAuthResponse(auth.token(), issued.rawToken(), accessTtlSeconds, auth.user());
    }

    /**
     * 移动端登出接口。
     * 如果请求体中携带了刷新令牌，则撤销该令牌。
     * 刷新令牌为可选参数，即使不传也能正常登出（仅清除客户端状态）。
     *
     * @param body    【可选的刷新令牌请求体，用于服务端撤销令牌】
     * @param request 【HTTP 请求，用于获取当前认证上下文和记录审计日志】
     * @return 【固定返回 {"ok": true}】
     */
    @PostMapping("/logout")
    Map<String, Object> logout(@RequestBody(required = false) MobileRefreshRequest body,
                               HttpServletRequest request) {
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            refreshTokens.revoke(body.refreshToken());
        }
        // 【尽力获取当前认证用户用于审计记录，未认证时 actor 为 null】
        var auth = SecurityContextHolder.getContext().getAuthentication();
        UserAccount actor = (auth != null && auth.getPrincipal() instanceof UserAccount u) ? u : null;
        audit.record(AuditAction.LOGOUT, actor, null, null, request, true, "channel=mobile");
        return Map.of("ok", true);
    }

    /**
     * 从 HTTP 请求中提取客户端真实 IP 地址。
     * 优先从 X-Forwarded-For 头获取（适用于反向代理/负载均衡场景），
     * 取第一个 IP（即最靠近客户端的 IP），否则回退到 RemoteAddr。
     *
     * @param request 【HTTP 请求】
     * @return 【客户端 IP 地址字符串】
     */
    private static String clientIp(HttpServletRequest request) {
        var xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
