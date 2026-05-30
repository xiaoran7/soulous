package com.soulous.task;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 【学习任务仓库接口——负责对 study_task 表进行持久化操作。
 *  提供按用户查询任务、按目标筛选任务、统计任务数量等能力。
 *  还包含将任务与目标解绑的自定义更新操作。
 *  由 TaskService 在任务的增删改查和目标管理流程中调用。】
 *
 * <p>English: Spring Data JPA repository for {@link StudyTask} entities.</p>
 */
public interface TaskRepository extends JpaRepository<StudyTask, Long> {

    /**
     * 【按创建时间倒序查询指定用户的所有学习任务，最新创建的任务排在最前面。】
     *
     * @param user 【关联的用户账号】
     * @return 【该用户的学习任务列表（按创建时间降序）】
     */
    List<StudyTask> findByUserOrderByCreatedAtDesc(UserAccount user);

    /**
     * 【统计指定用户在给定时间点之后创建的任务数量，用于仪表盘展示。】
     *
     * @param user  【关联的用户账号】
     * @param since 【起始时间点，只统计 createdAt > since 的任务】
     * @return 【满足条件的任务数量】
     */
    long countByUserAndCreatedAtAfter(UserAccount user, LocalDateTime since);

    /**
     * 【查询指定用户下属于某一目标的所有任务，按创建时间倒序排列。
     *  用于目标详情页展示该目标下的任务列表。】
     *
     * @param user   【关联的用户账号】
     * @param goalId 【目标 ID】
     * @return 【属于该目标的任务列表（按创建时间降序）】
     */
    List<StudyTask> findByUserAndGoalIdOrderByCreatedAtDesc(UserAccount user, Long goalId);

    /**
     * 【将指定用户下绑定到某目标的所有任务的 goalId 置为 null，即解除任务与目标的关联。
     *  使用 JPQL 更新语句直接在数据库层面执行，避免先查后改的性能开销。
     *  通常在用户删除目标时调用。】
     *
     * @param user   【关联的用户账号】
     * @param goalId 【要解绑的目标 ID】
     * @return 【实际更新的记录数】
     */
    @Modifying
    @Transactional
    @Query("update StudyTask t set t.goalId = null where t.user = ?1 and t.goalId = ?2")
    int unbindFromGoal(UserAccount user, Long goalId);
}
