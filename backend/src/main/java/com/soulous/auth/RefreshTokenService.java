package com.soulous.auth;

import com.soulous.audit.AuditAction;
import com.soulous.audit.AuditService;
import com.soulous.common.exception.UnauthorizedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 刷新令牌的全生命周期管理服务。
 * 负责颁发、验证、轮换和撤销刷新令牌。
 * 原始令牌为 256 位随机值经 Base64URL 编码，数据库中仅存储其 SHA-256 哈希值
 * （即使数据库泄露，攻击者也无法反推出原始令牌）。
 *
 * <p>Issues, validates, rotates, and revokes refresh tokens. The raw token is a
 * 256-bit random value base64url-encoded; only its SHA-256 hash hits the DB.</p>
 */
@Service
public class RefreshTokenService {
    /** 密码学安全随机数生成器，用于生成 256 位随机令牌 */
    private static final SecureRandom RNG = new SecureRandom();
    /** 无填充的 Base64URL 编码器，确保令牌可在 URL 和 Cookie 中安全传输 */
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /** 刷新令牌持久化仓库 */
    private final RefreshTokenRepository tokens;
    /** 审计服务，用于记录令牌重放等安全事件 */
    private final AuditService audit;
    /** 刷新令牌的有效期时长 */
    private final Duration ttl;
    /** Micrometer 指标注册表，用于记录令牌重放等监控指标 */
    private final MeterRegistry meterRegistry;

    /**
     * 自引用注入，用于通过 Spring 代理调用 {@link #panicRevokeAllForUser}。
     * 这样该方法的 REQUIRES_NEW 事务传播级别才能生效——直接通过 this 调用会跳过代理，
     * 导致新事务不会开启，撤销操作会随外层事务一起回滚。
     * 使用 @Lazy 注解避免循环依赖的构造警告。
     *
     * <p>Self-reference used to invoke {@link #panicRevokeAllForUser} through Spring's proxy so its
     * REQUIRES_NEW transaction actually opens a new tx — otherwise an in-class call goes directly
     * through {@code this} and skips the proxy, which would inherit the failing rotate() tx and
     * be rolled back along with it. @Lazy avoids the circular-construction warning.</p>
     */
    @Autowired @Lazy
    private RefreshTokenService self;

    RefreshTokenService(RefreshTokenRepository tokens,
                        AuditService audit,
                        MeterRegistry meterRegistry,
                        @Value("${soulous.jwt.refresh-ttl-days:30}") int refreshTtlDays) {
        this.tokens = tokens;
        this.audit = audit;
        this.meterRegistry = meterRegistry;
        this.ttl = Duration.ofDays(Math.max(1, refreshTtlDays));
    }

    /** 颁发的刷新令牌结果对：包含客户端应存储的原始令牌和数据库持久化实体 */
    public record Issued(String rawToken, RefreshToken entity) {}

    /**
     * 颁发一个新的刷新令牌。
     * 生成 256 位随机令牌，将其 SHA-256 哈希值持久化到数据库，
     * 原始令牌返回给客户端存储。
     *
     * @param user      【令牌所属的用户账户】
     * @param userAgent 【客户端 User-Agent，用于会话识别和管理】
     * @param ip        【客户端 IP 地址，用于安全审计】
     * @return 【包含原始令牌和持久化实体的 Issued 对象】
     */
    @Transactional
    public Issued issue(UserAccount user, String userAgent, String ip) {
        var raw = randomToken();
        var entity = new RefreshToken();
        entity.user = user;
        entity.tokenHash = hash(raw);
        entity.expiresAt = LocalDateTime.now().plus(ttl);
        entity.userAgent = truncate(userAgent, 255);
        entity.ip = truncate(ip, 64);
        tokens.save(entity);
        return new Issued(raw, entity);
    }

    /**
     * 将原始刷新令牌解析为所属用户。
     * 过期或已撤销的令牌视为认证失败。同时更新 lastUsedAt 时间戳，
     * 以便后续可以向用户展示"活跃会话"列表。
     *
     * <p>Resolves a raw refresh token to its owning user, treating an expired or
     * revoked token as an authentication failure. Updates lastUsedAt as a side
     * effect so we can later display "active sessions".</p>
     *
     * @param rawToken 【客户端提供的原始刷新令牌】
     * @return 【令牌所属的用户账户】
     * @throws UnauthorizedException 【当令牌为空、无效、过期或已撤销时抛出】
     */
    @Transactional
    public UserAccount resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new UnauthorizedException("missing refresh token");
        var entity = tokens.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
        if (!entity.isActive(LocalDateTime.now())) {
            throw new UnauthorizedException("refresh token expired or revoked");
        }
        entity.lastUsedAt = LocalDateTime.now();
        tokens.save(entity);
        return entity.user;
    }

    /**
     * 原子性令牌轮换：验证旧令牌 -> 标记旧令牌为已撤销 -> 颁发新令牌。
     * 如果旧令牌已被撤销（即被重复使用），视为可能的重放攻击，
     * 将撤销该用户的所有令牌——当被盗令牌同时被攻击者和受害者使用时，
     * 应将所有人踢出并强制重新登录。
     *
     * <p>Atomic rotate: validate the old token, mark it revoked, issue a fresh one.
     * Returns the new raw token. If the old token has already been used (revoked),
     * we treat that as a likely replay attack and revoke ALL the user's tokens —
     * a stolen-token cookie that's been used by both attacker and victim should
     * boot everyone out and force a fresh login.</p>
     *
     * @param oldRawToken 【待轮换的旧原始刷新令牌】
     * @param userAgent   【客户端 User-Agent，用于新令牌的会话记录和审计】
     * @param ip          【客户端 IP 地址，用于安全审计】
     * @return 【新颁发的 Issued 对象，包含新原始令牌和持久化实体】
     * @throws UnauthorizedException 【当令牌为空、无效、过期或检测到重放攻击时抛出】
     */
    @Transactional
    public Issued rotate(String oldRawToken, String userAgent, String ip) {
        if (oldRawToken == null || oldRawToken.isBlank()) {
            throw new UnauthorizedException("missing refresh token");
        }
        var existing = tokens.findByTokenHash(hash(oldRawToken))
                .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
        var now = LocalDateTime.now();

        if (existing.revokedAt != null) {
            // 【令牌在撤销后被重复使用——可能已被泄露。在同一事务中撤销该用户的所有令牌】
            // Token re-use after revoke — possibly compromised. Nuke all tokens for this user
            // in a SEPARATE transaction so the revocation survives the rollback we're about to
            // trigger by throwing 401.
            self.panicRevokeAllForUser(existing.user);
            // 【记录重放攻击指标，便于 Prometheus/Grafana 监控告警】
            meterRegistry.counter("soulous.refresh_token.replayed.total").increment();
            // 【必须使用 REQUIRES_NEW 变体——当前事务即将因抛出 401 而回滚，
            // 如果不开启新事务，审计记录也会被一起回滚】
            // Must use the REQUIRES_NEW variant — we're about to throw and roll back this tx,
            // which would otherwise eat the audit insert along with it.
            audit.recordInNewTransaction(AuditAction.REFRESH_TOKEN_REPLAYED, existing.user, null,
                    "USER", existing.user.id, null, false,
                    "replayed refresh_token id=" + existing.id + " ua=" + userAgent + " ip=" + ip);
            throw new UnauthorizedException("refresh token replayed; all sessions revoked");
        }
        if (existing.expiresAt.isBefore(now)) {
            throw new UnauthorizedException("refresh token expired");
        }

        existing.revokedAt = now;
        tokens.save(existing);
        return issue(existing.user, userAgent, ip);
    }

    /**
     * 撤销指定的刷新令牌。如果令牌为空或已撤销则静默忽略。
     *
     * @param rawToken 【待撤销的原始刷新令牌】
     */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        tokens.findByTokenHash(hash(rawToken)).ifPresent(t -> {
            if (t.revokedAt == null) {
                t.revokedAt = LocalDateTime.now();
                tokens.save(t);
            }
        });
    }

    /**
     * 撤销指定用户的所有未撤销刷新令牌。
     * 用于密码修改、登出全部会话等场景。
     *
     * @param user 【目标用户】
     * @return 【被撤销的令牌数量】
     */
    @Transactional
    public int revokeAllForUser(UserAccount user) {
        return tokens.revokeAllForUser(user, LocalDateTime.now());
    }

    /**
     * 与 {@link #revokeAllForUser} 相同，但在独立的新事务中执行。
     * 详见 {@link #self} 字段的注释，了解为什么需要独立事务。
     *
     * <p>Same as {@link #revokeAllForUser} but in a fresh transaction; see {@link #self} for why.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void panicRevokeAllForUser(UserAccount user) {
        tokens.revokeAllForUser(user, LocalDateTime.now());
    }

    /**
     * 定时垃圾回收任务：每天凌晨 4 点执行。
     * 物理删除已过期或已撤销超过 30 天的刷新令牌记录，
     * 防止令牌表无限膨胀。
     *
     * <p>Daily sweep at 04:00: physically remove rows that have been expired or revoked for >30d.</p>
     */
    @Scheduled(cron = "${soulous.jwt.refresh-gc-cron:0 0 4 * * *}")
    @Transactional
    public void garbageCollect() {
        var cutoff = LocalDateTime.now().minusDays(30);
        int n = tokens.deleteExpiredOrLongRevoked(cutoff);
        if (n > 0) {
            org.slf4j.LoggerFactory.getLogger(RefreshTokenService.class)
                    .info("RefreshToken GC: deleted {} expired/revoked rows older than {}", n, cutoff);
        }
    }

    /**
     * 生成 256 位（32 字节）随机令牌并进行 Base64URL 编码。
     * 使用 SecureRandom 确保密码学安全性。
     *
     * @return 【Base64URL 编码的随机令牌字符串（43 个字符）】
     */
    static String randomToken() {
        var bytes = new byte[32];
        RNG.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    /**
     * 计算原始令牌的 SHA-256 哈希值（十六进制字符串）。
     * 数据库中仅存储哈希值，即使数据库泄露也无法反推出原始令牌。
     *
     * @param raw 【原始令牌字符串】
     * @return 【SHA-256 哈希值的十六进制表示（64 个字符）】
     */
    static String hash(String raw) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var h = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * 截断字符串到指定最大长度，用于防止超长 User-Agent 或 IP 写入数据库时溢出字段。
     *
     * @param s   【待截断的字符串，可为 null】
     * @param max 【最大允许长度】
     * @return 【截断后的字符串，null 输入返回 null】
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
