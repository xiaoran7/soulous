package com.soulous.focus;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 【专注会话数据仓库接口：继承 JpaRepository 提供 FocusSession 实体的 CRUD 操作，
 *  并定义按用户、状态、时间范围查询的自定义方法。】
 */
public interface FocusSessionRepository extends JpaRepository<FocusSession, Long> {
    /**
     * 【获取指定用户的所有专注会话，按创建时间倒序排列（最近的在前）。】
     */
    List<FocusSession> findByUserOrderByCreatedAtDesc(UserAccount user);

    /**
     * 【获取指定用户中状态在给定集合内的最新一个会话。
     *  用于查找当前活跃的会话（RUNNING 或 PAUSED 状态）。
     *  返回 Optional 以处理不存在的情况。】
     */
    Optional<FocusSession> findFirstByUserAndStatusInOrderByCreatedAtDesc(UserAccount user, Collection<FocusStatus> statuses);

    /**
     * 【获取指定用户中特定状态且在某个时间点之后创建的会话列表。
     *  可用于统计某段时间内的专注完成情况。】
     */
    List<FocusSession> findByUserAndStatusAndCreatedAtAfter(UserAccount user, FocusStatus status, LocalDateTime since);
}
