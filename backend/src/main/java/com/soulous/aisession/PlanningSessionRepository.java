package com.soulous.aisession;

import com.soulous.auth.UserAccount;
import com.soulous.goal.Goal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 【规划会话仓储接口：提供 PlanningSession 实体的数据库查询能力】
 *
 * <p>继承 JpaRepository 获得基础 CRUD，额外定义按目标、用户、状态等维度的查询方法。
 * Spring Data JPA 根据方法名自动生成 SQL 实现。</p>
 */
public interface PlanningSessionRepository extends JpaRepository<PlanningSession, Long> {

    /**
     * 【查找目标下指定状态的最新会话：用于 check-in 时判断是否已有活跃会话可复用】
     *
     * @param goal   【目标实体】
     * @param states 【候选状态列表，通常为 DRAFTING 和 PLAN_PROPOSED】
     * @return 【按开始时间降序排列的第一个匹配会话，可能为空】
     */
    Optional<PlanningSession> findFirstByGoalAndStateInOrderByStartedAtDesc(Goal goal, List<SessionState> states);

    /**
     * 【按用户查询所有会话：返回该用户名下全部会话，按开始时间降序排列】
     *
     * @param user 【用户实体】
     * @return 【会话列表】
     */
    List<PlanningSession> findByUserOrderByStartedAtDesc(UserAccount user);

    /**
     * 【按目标查询所有会话：返回某目标关联的全部会话，按开始时间降序排列】
     *
     * @param goal 【目标实体】
     * @return 【会话列表】
     */
    List<PlanningSession> findByGoalOrderByStartedAtDesc(Goal goal);

    /**
     * 【按单一状态和截止时间查询过期会话：用于定时清理任务】
     *
     * @param state  【会话状态】
     * @param cutoff 【截止时间，最后活跃时间早于此值的会话将被选中】
     * @return 【过期会话列表】
     */
    List<PlanningSession> findByStateAndLastActivityAtBefore(SessionState state, LocalDateTime cutoff);

    /**
     * 【按多状态和截止时间查询过期会话：批量清理 DRAFTING 和 PLAN_PROPOSED 状态的僵尸会话】
     *
     * @param states 【候选状态列表】
     * @param cutoff 【截止时间】
     * @return 【过期会话列表】
     */
    List<PlanningSession> findByStateInAndLastActivityAtBefore(List<SessionState> states, LocalDateTime cutoff);
}
