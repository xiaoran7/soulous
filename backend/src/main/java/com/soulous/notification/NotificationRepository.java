package com.soulous.notification;

import com.soulous.auth.UserAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 【通知数据仓库接口：继承 JpaRepository 提供基础 CRUD，
 * 并定义通知查询和批量更新的自定义方法。】
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    /**
     * 【按用户分页查询所有通知，按创建时间降序排列】
     *
     * @param user     【用户对象】
     * @param pageable 【分页参数】
     * @return 【分页结果】
     */
    Page<Notification> findByUserOrderByCreatedAtDesc(UserAccount user, Pageable pageable);

    /**
     * 【按用户分页查询未读通知（read_at 为 NULL），按创建时间降序排列】
     *
     * @param user     【用户对象】
     * @param pageable 【分页参数】
     * @return 【分页结果】
     */
    Page<Notification> findByUserAndReadAtIsNullOrderByCreatedAtDesc(UserAccount user, Pageable pageable);

    /**
     * 【统计用户未读通知数量】
     *
     * @param user 【用户对象】
     * @return 【未读通知数量】
     */
    long countByUserAndReadAtIsNull(UserAccount user);

    /**
     * 【批量标记用户所有未读通知为已读，通过 JPQL 直接更新数据库，避免逐条加载实体】
     *
     * @param user 【用户对象】
     * @param now  【当前时间，作为 read_at 的值】
     * @return 【被更新的记录数】
     */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.user = :user AND n.readAt IS NULL")
    int markAllReadForUser(@Param("user") UserAccount user, @Param("now") LocalDateTime now);
}
