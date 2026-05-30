package com.soulous.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT（JSON Web Token）服务。
 * 负责访问令牌的颁发和解析。令牌使用 HMAC-SHA 算法签名，
 * 包含用户 ID、角色、用户名和令牌版本号等声明。
 *
 * <p>密钥从配置项 soulous.jwt.secret 读取，不足 32 字节时会自动填充
 * （仅适用于开发环境，生产环境必须配置足够长度的随机密钥）。
 */
@Service
public class JwtService {
    /** HMAC-SHA 签名密钥，从配置项派生 */
    private final SecretKey key;
    /** 访问令牌的有效期（秒） */
    private final long ttlSeconds;

    /**
     * 构造 JWT 服务。
     * 密钥不足 32 字节时用 '0' 填充（仅开发环境兼容），
     * 生产环境应配置至少 256 位的随机密钥。
     *
     * @param secret    【HMAC-SHA 签名密钥字符串】
     * @param ttlSeconds 【访问令牌有效期（秒）】
     */
    JwtService(@Value("${soulous.jwt.secret:soulous-default-dev-secret-please-change-in-production-32bytes}") String secret,
               // 【access-ttl-seconds 是新配置项（默认 1 小时）。soulous.jwt.ttl-seconds 是旧别名，
               // 仅当 access-ttl-seconds 未配置时生效，用于兼容之前配置了 7 天有效期的运维环境】
               // access-ttl-seconds is the new knob (1h default). soulous.jwt.ttl-seconds
               // is kept as a legacy alias for ops that pinned the previous 7d value —
               // it wins only if access-ttl-seconds is left at its default.
               @Value("${soulous.jwt.access-ttl-seconds:${soulous.jwt.ttl-seconds:3600}}") long ttlSeconds) {
        var raw = secret.getBytes(StandardCharsets.UTF_8);
        // 【密钥不足 256 位（32 字节）时用 '0' 填充，确保 HMAC-SHA 算法的最低安全要求】
        if (raw.length < 32) {
            var padded = new byte[32];
            System.arraycopy(raw, 0, padded, 0, raw.length);
            for (int i = raw.length; i < 32; i++) padded[i] = (byte) '0';
            raw = padded;
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 为指定用户颁发 JWT 访问令牌。
     * 令牌包含以下声明：sub（用户 ID）、role（角色名）、username（用户名）、
     * tv（令牌版本号，用于实现令牌撤销）、iat（签发时间）、exp（过期时间）。
     *
     * @param user 【用户账户实体】
     * @return 【签名后的 JWT 字符串】
     */
    public String issue(UserAccount user) {
        var now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.id))
                .claim("role", user.role.name())
                .claim("username", user.username)
                .claim("tv", user.tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /** 解析后的 JWT 令牌数据：包含用户 ID 和令牌版本号 */
    public record ParsedToken(Long userId, int tokenVersion) {}

    /**
     * 解析并验证 JWT 令牌。
     * 验证签名有效性和过期时间，提取用户 ID 和令牌版本号。
     *
     * @param token 【JWT 字符串】
     * @return 【解析后的 ParsedToken，包含 userId 和 tokenVersion】
     * @throws io.jsonwebtoken.JwtException 【当令牌签名无效或已过期时抛出】
     */
    public ParsedToken parse(String token) {
        var jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        var payload = jws.getPayload();
        var id = Long.valueOf(payload.getSubject());
        // 【提取令牌版本号（tv），用于校验令牌是否已被撤销；兼容旧令牌中不含 tv 字段的情况】
        var tv = payload.get("tv");
        int version = tv instanceof Number n ? n.intValue() : 0;
        return new ParsedToken(id, version);
    }
}
