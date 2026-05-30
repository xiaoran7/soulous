package com.soulous.task;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 【任务提交仓库接口——负责对 task_submission 表进行持久化操作。
 *  提交记录是用户完成任务后产生的凭证，包含文本证明、代码片段、截图等。
 *  该仓库提供按用户查询、按状态计数、按任务管理等功能，
 *  由 TaskService 在提交 / 审核 / 统计流程中调用。】
 *
 * <p>English: Spring Data JPA repository for {@link TaskSubmission} entities.</p>
 */
public interface SubmissionRepository extends JpaRepository<TaskSubmission, Long> {

    /**
     * 【按创建时间倒序查询指定用户的所有提交记录，最新提交排在最前面。】
     *
     * @param user 【关联的用户账号】
     * @return 【该用户的提交记录列表（按创建时间降序）】
     */
    List<TaskSubmission> findByUserOrderByCreatedAtDesc(UserAccount user);

    /**
     * 【查询所有提交记录并按创建时间倒序排列，用于管理员全局查看。】
     *
     * @return 【全部提交记录列表（按创建时间降序）】
     */
    List<TaskSubmission> findAllByOrderByCreatedAtDesc();

    /**
     * 【统计指定用户在给定时间点之后的提交次数，用于活跃度 / 统计面板。】
     *
     * @param user  【关联的用户账号】
     * @param since 【起始时间点，只统计 createdAt > since 的提交】
     * @return 【满足条件的提交数量】
     */
    long countByUserAndCreatedAtAfter(UserAccount user, LocalDateTime since);

    /**
     * 【统计指定用户在给定状态集合内的提交数量，例如统计"已通过"的提交数。】
     *
     * @param user     【关联的用户账号】
     * @param statuses 【要匹配的状态集合，如 APPROVED、PENDING 等】
     * @return 【状态在指定集合内的提交数量】
     */
    long countByUserAndStatusIn(UserAccount user, Collection<SubmissionStatus> statuses);

    /**
     * 【判断是否存在与指定任务关联的提交记录，用于在删除任务前检查引用完整性。】
     *
     * @param task 【要检查的学习任务】
     * @return 【存在关联提交返回 true，否则返回 false】
     */
    boolean existsByTask(StudyTask task);

    /**
     * 【查询指定任务的所有提交记录，用于任务详情页展示提交历史。】
     *
     * @param task 【关联的学习任务】
     * @return 【该任务的所有提交记录列表】
     */
    List<TaskSubmission> findByTask(StudyTask task);

    /**
     * 【根据任务删除所有关联的提交记录。
     *  标注 @Modifying + @Transactional 使其成为可写事务操作。
     *  通常在删除任务前由 TaskService 调用，以清理关联数据。】
     *
     * @param task 【要删除其提交记录的任务】
     */
    @Modifying
    @Transactional
    void deleteByTask(StudyTask task);
}
