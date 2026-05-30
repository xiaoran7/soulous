package com.soulous.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 【服务端刷新令牌实体，对应数据库 refresh_token 表。
 *  原始 token 值绝不存储——仅保留 SHA-256 哈希，因此即使数据库泄露也无法直接用于冒充用户。
 *
 *  生命周期：
 *  1. 用户登录/注册时创建新行；
 *  2. 每次成功刷新时轮转（旧行标记 {@link #revokedAt}，新行插入）；
 *  3. 登出 / 登出全部设备时显式撤销；
 *  4. 超过 {@link #expiresAt} 后自然过期。
 *
 *  索引设计：
 *  - token_hash 唯一索引用于快速校验令牌合法性；
 *  - user_id 索引用于按用户查询活跃会话列表。
 *
 * <p>One server-side refresh token row. Raw token value is NEVER stored — only the
 * SHA-256 hash, so a leak of this table is not directly usable for impersonation.</p>
 *
 * <p>Lifecycle: created at login/register, rotated on every successful refresh
 * (old row marked {@link #revokedAt}, new one inserted), explicitly revoked on
 * logout / logout-all, naturally expired after {@link #expiresAt}.</p>
 */
@Entity
@Table(
        name = "refresh_token",
        indexes = {
                @Index(name = "idx_refresh_token_hash", columnList = "token_hash", unique = true),
                @Index(name = "idx_refresh_token_user", columnList = "user_id")
        }
)
public class RefreshToken {
    /** 【主键，自增 ID】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属用户，多对一关系。每条刷新令牌归属于一个用户，级联删除由数据库外键策略控制】 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【令牌的 SHA-256 哈希值（64 字符十六进制字符串），用于安全校验而不暴露原始 token】 */
    @Column(name = "token_hash", nullable = false, length = 64)
    public String tokenHash;

    /** 【令牌过期时间，超过此时间后该令牌不可用。通常为创建后 7-30 天】 */
    @Column(name = "expires_at", nullable = false)
    public LocalDateTime expiresAt;

    /** 【撤销时间。非 null 表示该令牌已被主动撤销（登出或轮转），不再有效】 */
    @Column(name = "revoked_at")
    public LocalDateTime revokedAt;

    /** 【创建时间，默认为当前时间，用于审计和排查问题】 */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    /** 【最后使用时间，每次刷新时更新，用于展示活跃会话信息】 */
    @Column(name = "last_used_at")
    public LocalDateTime lastUsedAt;

    /**
     * 【用户代理字符串（浏览器/设备信息），在令牌签发时捕获，
     *  用于"查看活跃会话"功能中展示设备信息，最多 255 字符】
     *
     * <p>Best-effort UA string captured on issue, for "see active sessions" later.</p>
     */
    @Column(name = "user_agent", length = 255)
    public String userAgent;

    /** 【客户端 IP 地址，在令牌签发时捕获，用于安全审计和异常登录检测】 */
    @Column(length = 64)
    public String ip;

    /**
     * 【判断该令牌当前是否有效：未被撤销且未过期。
     *  这是核心的状态检查方法，在每次刷新请求时被调用。】
     *
     * @param now 【当前时间，通常由调用方传入 LocalDateTime.now()】
     * @return 【true 表示令牌有效（未撤销且未过期），false 表示已失效】
     *
     * <p>English: Returns true if the token is neither revoked nor expired.</p>
     */
    public boolean isActive(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
