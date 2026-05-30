package com.soulous.moderation;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 【审核日志数据仓库接口】
 * 继承 JpaRepository，提供 {@link ModerationLog} 实体的 CRUD 操作。
 * 针对审核模块的查询需求定义了自定义查询方法，支持按用户、判定等级和会话查询。
 */
public interface ModerationLogRepository extends JpaRepository<ModerationLog, Long> {
    /**
     * 【按用户查询审核日志】
     * 返回指定用户的所有审核日志，按创建时间降序排列。
     *
     * @param user 用户实体
     * @return 审核日志列表
     */
    List<ModerationLog> findByUserOrderByCreatedAtDesc(UserAccount user);

    /**
     * 【按用户和判定等级查询审核日志】
     * 返回指定用户特定判定等级的审核日志，按创建时间降序排列。
     *
     * @param user    用户实体
     * @param verdict 审核判定等级（PASS / FLAG / BLOCK）
     * @return 审核日志列表
     */
    List<ModerationLog> findByUserAndVerdictOrderByCreatedAtDesc(UserAccount user, ModerationVerdict verdict);

    /**
     * 【按会话 ID 查询审核日志】
     * 返回指定会话的所有审核日志，按创建时间降序排列。
     *
     * @param sessionId 会话 ID
     * @return 审核日志列表
     */
    List<ModerationLog> findBySessionIdOrderByCreatedAtDesc(Long sessionId);

    /**
     * 【统计用户近期违规次数】
     * 统计指定用户在给定时间之后特定判定等级的审核日志数量，
     * 用于惯犯升级机制的判定。
     *
     * @param user    用户实体
     * @param verdict 审核判定等级
     * @param after   时间下限
     * @return 匹配的审核日志数量
     */
    long countByUserAndVerdictAndCreatedAtAfter(UserAccount user, ModerationVerdict verdict, LocalDateTime after);
}
