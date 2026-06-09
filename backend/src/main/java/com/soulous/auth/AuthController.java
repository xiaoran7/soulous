package com.soulous.auth;

import com.soulous.audit.AuditAction;
import com.soulous.audit.AuditService;
import com.soulous.common.exception.UnauthorizedException;
import com.soulous.common.ratelimit.RateLimit;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Web 端认证控制器。
 * 处理基于 Cookie 的身份认证流程，包括注册、登录、登出、令牌刷新、密码修改等。
 * 与移动端 {@link AuthMobileController} 的主要区别是令牌通过 HttpOnly Cookie 传递，
 * 且注册/登录需要验证码（CAPTCHA）。
 *
 * <p>所有写操作均记录审计日志，安全敏感操作（如密码修改）会撤销所有现有刷新令牌。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    /** 短期访问令牌的 Cookie 名称，每次 API 请求都会携带 */
    public static final String COOKIE_NAME = "soulous_token";
    /**
     * 长期刷新令牌的 Cookie 名称。Cookie 路径限定为 /api/auth，
     * 仅在认证相关接口上传输，不会随其他 API 调用或静态资源请求发送，
     * 最小化意外泄露的风险（如日志、中间代理）。
     *
     * <p>Long-lived refresh token cookie. Path is scoped to /api/auth so the cookie is
     * NOT sent on other API calls (or on /uploads asset fetches) — minimises the
     * surface area for accidental exposure (logs, intermediaries).</p>
     */
    public static final String REFRESH_COOKIE_NAME = "soulous_refresh";
    /** 刷新令牌 Cookie 的路径限定，仅在 /api/auth 路径下发送 */
    public static final String REFRESH_COOKIE_PATH = "/api/auth";

    /** 用户服务，负责注册、登录、密码管理等核心业务 */
    private final UserService users;
    /** 验证码服务，用于生成和校验 CAPTCHA（登录用） */
    private final CaptchaService captcha;
    /** 邮箱验证码服务，用于注册时的邮箱验证码发放与校验 */
    private final EmailCodeService emailCodes;
    /** 刷新令牌服务，负责令牌的颁发、轮换和撤销 */
    private final RefreshTokenService refreshTokens;
    /** 审计服务，用于记录安全相关操作日志 */
    private final AuditService audit;
    /** Cookie 是否设置 Secure 标志（仅 HTTPS 传输），生产环境应为 true */
    private final boolean cookieSecure;
    /** 访问令牌有效期（秒） */
    private final long accessTtlSeconds;
    /** 刷新令牌有效期（秒） */
    private final long refreshTtlSeconds;

    AuthController(UserService users,
                   CaptchaService captcha,
                   EmailCodeService emailCodes,
                   RefreshTokenService refreshTokens,
                   AuditService audit,
                   @Value("${soulous.cookie.secure:false}") boolean cookieSecure,
                   @Value("${soulous.jwt.access-ttl-seconds:3600}") long accessTtlSeconds,
                   @Value("${soulous.jwt.refresh-ttl-days:30}") int refreshTtlDays) {
        this.users = users;
        this.captcha = captcha;
        this.emailCodes = emailCodes;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.cookieSecure = cookieSecure;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = Math.max(1, refreshTtlDays) * 86_400L;
    }

    /**
     * 获取验证码接口。
     * 生成一个新的 CAPTCHA 并返回其 ID 和图片，前端展示后用户输入验证码提交。
     *
     * @return 【验证码响应，包含 captchaId 和验证码图片数据】
     */
    @GetMapping("/captcha")
    CaptchaResponse captcha() {
        return captcha.issue();
    }

    /**
     * 发送注册邮箱验证码接口。
     * 向请求邮箱发送一个一次性 6 位验证码，注册时提交以验证邮箱归属。
     * 限流防刷；同一邮箱另有重发冷却（由 EmailCodeService 控制）。
     * 未配置 SMTP 时验证码回退到后端日志（开发期）。
     *
     * @param request 【包含目标邮箱的请求体】
     * @return 【固定返回 {"ok": true}，不回显验证码】
     */
    @PostMapping("/email-code")
    @RateLimit(name = "auth-email-code", capacity = 3, refillTokens = 3, refillPeriod = 1)
    Map<String, Object> emailCode(@Valid @RequestBody EmailCodeRequest request) {
        emailCodes.requestCode(request.email());
        return Map.of("ok", true);
    }

    /**
     * Web 端注册接口。
     * 先校验两次密码是否一致，再校验邮箱验证码（替代图形验证码），然后调用 UserService 创建用户。
     * 注册成功后颁发访问令牌和刷新令牌（通过 Cookie 设置）。
     *
     * @param request    【包含用户名、密码、确认密码、昵称、邮箱、邮箱验证码的注册请求】
     * @param httpRequest 【HTTP 请求，用于获取 User-Agent 和客户端 IP】
     * @param response    【HTTP 响应，用于设置认证 Cookie】
     * @return 【认证响应，包含 JWT 令牌和用户信息】
     */
    @PostMapping("/register")
    @RateLimit(name = "auth-register", capacity = 5, refillTokens = 5, refillPeriod = 1)
    AuthResponse register(@Valid @RequestBody RegisterWithEmailCodeRequest request,
                          HttpServletRequest httpRequest,
                          HttpServletResponse response) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new com.soulous.common.exception.BadRequestException("两次输入的密码不一致");
        }
        emailCodes.verify(request.email(), request.emailCode());
        var auth = users.register(request.toService());
        issueCookies(httpRequest, response, auth, users.byToken(auth.token()));
        return auth;
    }

    /**
     * Web 端登录接口。
     * 先校验验证码，再验证用户名密码。登录失败时记录审计日志（包含失败原因），
     * 但 API 响应统一返回通用错误信息，防止通过错误信息枚举有效用户名。
     *
     * @param request     【包含用户名、密码和验证码信息的登录请求】
     * @param httpRequest 【HTTP 请求，用于获取 User-Agent 和客户端 IP】
     * @param response    【HTTP 响应，用于设置认证 Cookie】
     * @return 【认证响应，包含 JWT 令牌和用户信息】
     */
    @PostMapping("/login")
    @RateLimit(name = "auth-login", capacity = 5, refillTokens = 5, refillPeriod = 1)
    AuthResponse login(@Valid @RequestBody LoginWithCaptchaRequest request,
                       HttpServletRequest httpRequest,
                       HttpServletResponse response) {
        captcha.verify(request.captchaId(), request.captchaCode());
        AuthResponse auth;
        try {
            auth = users.login(request.toService());
        } catch (UnauthorizedException ex) {
            // 【记录登录失败的审计日志，包含尝试的用户名（但 API 响应不泄露用户名是否存在）】
            // Record the attempted username (without leaking whether it exists vs bad password
            // through the API response — which stays generic). The audit row gets the raw input.
            audit.record(AuditAction.LOGIN_FAILED, null, request.username(), null, null,
                    httpRequest, false, "login failed: " + ex.getMessage());
            throw ex;
        }
        var actor = users.byToken(auth.token());
        issueCookies(httpRequest, response, auth, actor);
        audit.record(AuditAction.LOGIN_SUCCESS, actor, null, null, httpRequest, true, null);
        return auth;
    }

    /**
     * Web 端登出接口。
     * 从请求中读取刷新令牌 Cookie 并撤销，然后清除访问令牌和刷新令牌的 Cookie。
     * 记录登出审计日志。
     *
     * @param request  【HTTP 请求，用于读取刷新令牌 Cookie】
     * @param response 【HTTP 响应，用于清除认证 Cookie】
     * @return 【固定返回 {"ok": true}】
     */
    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        var refresh = readRefreshCookie(request);
        if (refresh != null) refreshTokens.revoke(refresh);
        writeAccessCookie(response, "", 0);
        writeRefreshCookie(response, "", 0);
        // 【尽力获取当前认证用户用于审计记录；未认证时 actor 为 null，审计日志仍可正常写入】
        // Best-effort: if no auth context exists this becomes a no-op via the null actor.
        var auth = SecurityContextHolder.getContext().getAuthentication();
        UserAccount actor = (auth != null && auth.getPrincipal() instanceof UserAccount u) ? u : null;
        audit.record(AuditAction.LOGOUT, actor, null, null, request, true, null);
        return Map.of("ok", true);
    }

    /**
     * 公开端点：读取刷新 Cookie，轮换令牌，返回新的访问令牌并在响应中设置新的访问/刷新 Cookie。
     * 前端的 401 拦截器会在访问令牌过期时调用此接口。
     *
     * <p>Public endpoint: takes the refresh cookie, rotates it, returns a new access token
     * in body + new access/refresh cookies. Frontend's 401 interceptor calls this.</p>
     *
     * @param request  【HTTP 请求，用于读取刷新令牌 Cookie】
     * @param response 【HTTP 响应，用于设置新的认证 Cookie】
     * @return 【新的认证响应，包含新的 JWT 令牌和用户信息】
     * @throws UnauthorizedException 【当刷新令牌 Cookie 缺失或无效时抛出】
     */
    @PostMapping("/refresh-token")
    @RateLimit(name = "auth-refresh", capacity = 30, refillTokens = 30, refillPeriod = 1)
    AuthResponse refreshToken(HttpServletRequest request, HttpServletResponse response) {
        var raw = readRefreshCookie(request);
        if (raw == null) throw new UnauthorizedException("missing refresh token");
        var issued = refreshTokens.rotate(raw, request.getHeader("User-Agent"), clientIp(request));
        var auth = users.refresh(issued.entity().user);
        writeAccessCookie(response, auth.token(), accessTtlSeconds);
        writeRefreshCookie(response, issued.rawToken(), refreshTtlSeconds);
        return auth;
    }

    /**
     * 旧版刷新接口——要求已有有效的访问令牌，仅刷新访问令牌（不轮换刷新令牌）。
     * 保留此接口以兼容旧版前端；新客户端应使用 {@link #refreshToken}。
     *
     * <p>Legacy endpoint — requires an already-valid access token and only refreshes IT
     * (no refresh-token rotation). Kept so older frontends keep working; new clients
     * should prefer {@link #refreshToken}.</p>
     *
     * @param response 【HTTP 响应，用于设置新的访问令牌 Cookie】
     * @return 【新的认证响应】
     */
    @PostMapping("/refresh")
    AuthResponse refresh(HttpServletResponse response) {
        var auth = users.refresh(currentUser());
        writeAccessCookie(response, auth.token(), accessTtlSeconds);
        return auth;
    }

    /**
     * 修改密码接口。
     * 验证当前密码后更新为新密码，同时撤销该用户的所有刷新令牌
     * （相当于"踢出除当前会话外的所有会话"），然后为当前会话颁发新的令牌对。
     *
     * @param body        【包含当前密码和新密码的请求体】
     * @param httpRequest 【HTTP 请求，用于获取 User-Agent 和客户端 IP】
     * @param response    【HTTP 响应，用于设置新的认证 Cookie】
     * @return 【新的认证响应】
     */
    @PostMapping("/password")
    AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest body,
                                HttpServletRequest httpRequest,
                                HttpServletResponse response) {
        var user = currentUser();
        var auth = users.changePassword(user, body.currentPassword(), body.newPassword());
        // 【密码修改意味着"踢出除当前会话外的所有现有会话"——最简单的方式是撤销所有刷新令牌，
        // 然后在下方为当前会话颁发新的令牌对】
        // Password change implies "boot every existing session except the one issuing the change"
        // — easiest way is to revoke all refresh tokens, then mint a fresh pair below.
        refreshTokens.revokeAllForUser(user);
        issueCookies(httpRequest, response, auth, users.byToken(auth.token()));
        audit.record(AuditAction.PASSWORD_CHANGED, user, null, null, httpRequest, true, null);
        return auth;
    }

    /**
     * 登出所有设备接口。
     * 递增用户的 tokenVersion（使所有现有 JWT 失效），
     * 撤销所有刷新令牌，清除 Cookie，并记录审计日志。
     * 返回被撤销的刷新令牌数量，便于前端展示。
     *
     * @param httpRequest 【HTTP 请求，用于记录审计日志】
     * @param response    【HTTP 响应，用于清除认证 Cookie】
     * @return 【包含 ok 状态和被撤销刷新令牌数量的 Map】
     */
    @PostMapping("/logout-all")
    Map<String, Object> logoutAll(HttpServletRequest httpRequest, HttpServletResponse response) {
        var user = currentUser();
        users.revokeAllTokens(user);
        int refreshCount = refreshTokens.revokeAllForUser(user);
        writeAccessCookie(response, "", 0);
        writeRefreshCookie(response, "", 0);
        audit.record(AuditAction.LOGOUT_ALL, user, null, null, httpRequest, true,
                "revokedRefreshTokens=" + refreshCount);
        return Map.of("ok", true, "revokedRefreshTokens", refreshCount);
    }

    /**
     * 从 SecurityContext 中获取当前已认证的用户。
     *
     * @return 【当前用户账户实体】
     * @throws UnauthorizedException 【当未认证或认证主体不是 UserAccount 时抛出】
     */
    private UserAccount currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserAccount user)) {
            throw new UnauthorizedException("Not authenticated");
        }
        return user;
    }

    /**
     * 颁发认证 Cookie：同时颁发刷新令牌，然后分别设置访问令牌和刷新令牌的 Cookie。
     *
     * @param request 【HTTP 请求，用于获取 User-Agent 和客户端 IP】
     * @param response 【HTTP 响应，用于设置 Cookie】
     * @param auth     【认证响应，包含访问令牌】
     * @param user     【用户账户实体，用于颁发刷新令牌】
     */
    private void issueCookies(HttpServletRequest request, HttpServletResponse response,
                              AuthResponse auth, UserAccount user) {
        var issued = refreshTokens.issue(user, request.getHeader("User-Agent"), clientIp(request));
        writeAccessCookie(response, auth.token(), accessTtlSeconds);
        writeRefreshCookie(response, issued.rawToken(), refreshTtlSeconds);
    }

    /**
     * 写入访问令牌 Cookie。
     * HttpOnly 防止 JS 读取（防 XSS），SameSite=Lax 防止 CSRF，
     * 路径为 "/"（全局发送）。
     *
     * @param response      【HTTP 响应】
     * @param token         【访问令牌字符串】
     * @param maxAgeSeconds 【Cookie 最大存活时间（秒）】
     */
    private void writeAccessCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        var cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 写入刷新令牌 Cookie。
     * 与访问令牌 Cookie 的区别：SameSite=Strict（更严格防 CSRF），
     * 路径限定为 /api/auth（仅在认证接口上传输，减少泄露面）。
     *
     * @param response      【HTTP 响应】
     * @param token         【刷新令牌字符串】
     * @param maxAgeSeconds 【Cookie 最大存活时间（秒）】
     */
    private void writeRefreshCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        var cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 从请求 Cookie 中读取刷新令牌。
     *
     * @param request 【HTTP 请求】
     * @return 【刷新令牌字符串，不存在或为空时返回 null】
     */
    private static String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (var c : cookies) {
            if (REFRESH_COOKIE_NAME.equals(c.getName())) {
                var v = c.getValue();
                return (v == null || v.isBlank()) ? null : v;
            }
        }
        return null;
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
