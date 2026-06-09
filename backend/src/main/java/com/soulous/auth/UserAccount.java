package com.soulous.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

/**
 * 【用户账户实体，对应数据库中的用户表。
 *  包含用户的基本信息（用户名、密码、昵称、邮箱、头像）以及安全相关字段（角色、令牌版本）。
 *  密码字段使用 @JsonIgnore 注解，确保序列化为 JSON 时不会泄露密码哈希。
 *  字段采用 public 访问修饰符以简化代码（无 getter/setter 样板），通过 JPA 直接访问。】
 *
 * <p>Core user account entity mapped to the users table.</p>
 */
@Entity
public class UserAccount {
    /** 【主键，数据库自增 ID】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【用户名，全局唯一，长度限制 64 字符。用于登录认证和 @提及 等场景】 */
    @Column(nullable = false, unique = true, length = 64)
    public String username;

    /**
     * 【密码哈希值（通常为 BCrypt 编码），绝不以明文存储。
     *  使用 @JsonIgnore 确保 JSON 序列化时排除此字段，防止密码泄露到前端或日志中。】
     *
     * <p>Hashed password — excluded from JSON serialization via @JsonIgnore.</p>
     */
    @Column(nullable = false)
    @JsonIgnore
    public String password;

    /** 【用户昵称，用于界面展示，不要求唯一。可为 null（未设置时前端可回退到 username）】 */
    public String nickname;

    /** 【用户邮箱地址，用于通知和密码找回等功能。可为 null】 */
    public String email;

    /** 【头像图片的访问 URL，由文件存储服务生成。可为 null（未设置头像时前端显示默认头像）】 */
    public String avatarUrl;

    /**
     * 【用户角色，以字符串形式存储在数据库中。
     *  默认为 USER（普通用户），管理员角色需要通过后台或管理接口设置。】
     */
    @Enumerated(EnumType.STRING)
    public UserRole role = UserRole.USER;

    /**
     * 【令牌版本号，用于实现"登出全部设备"功能。
     *  每次执行"登出全部"操作时自增，使得所有旧版本签发的令牌失效。
     *  JWT 令牌中会包含签发时的版本号，校验时与数据库值比对。】
     *
     * <p>Incremented on "logout all" to invalidate every existing refresh token.</p>
     */
    @Column(nullable = false)
    public int tokenVersion = 0;

    /** 【金币余额：完成任务/打卡/专注赚取，用于宠物市场购买。账号级共享（与每宠独立的经验不同）。】 */
    @Column(nullable = false)
    public int coinBalance = 0;

    /** 【账户创建时间，在实体持久化时自动设置，用于审计和展示】 */
    public LocalDateTime createdAt = LocalDateTime.now();

    /** 【账户最后更新时间，在每次修改资料时更新，用于审计和数据同步】 */
    public LocalDateTime updatedAt = LocalDateTime.now();
}
