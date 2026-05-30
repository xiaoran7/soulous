package com.soulous.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 刷新令牌的 JPA 持久化仓库。
 * 提供按令牌哈希查询、批量撤销和过期清理等自定义查询方法。
 * 继承 JpaRepository 获得基本的 CRUD 操作。
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    /**
     * 通过令牌的 SHA-256 哈希值查找刷新令牌实体。
     * 数据库中仅存储哈希值，此方法用于将客户端提交的原始令牌映射到对应实体。
     *
     * @param tokenHash 【令牌的 SHA-256 哈希值（十六进制字符串）】
     * @return 【匹配的刷新令牌实体，不存在时返回 Optional.empty()】
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 批量撤销指定用户的所有未撤销刷新令牌。
     * 使用 JPQL 批量更新，比逐条撤销更高效。
     * 用于密码修改、登出全部设备、安全事件响应等场景。
     *
     * @param user 【目标用户】
     * @param now  【撤销时间戳】
     * @return 【被撤销的令牌数量】
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.user = :user AND r.revokedAt IS NULL")
    int revokeAllForUser(@Param("user") UserAccount user, @Param("now") LocalDateTime now);

    /**
     * 物理删除已过期或已撤销超过指定时间的刷新令牌记录。
     * 由定时垃圾回收任务调用，防止令牌表无限膨胀。
     *
     * @param cutoff 【截止时间，早于此时间的过期/撤销记录将被删除】
     * @return 【被删除的记录数量】
     */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :cutoff OR (r.revokedAt IS NOT NULL AND r.revokedAt < :cutoff)")
    int deleteExpiredOrLongRevoked(@Param("cutoff") LocalDateTime cutoff);
}
