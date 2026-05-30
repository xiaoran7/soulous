package com.soulous.pet;

import com.soulous.auth.UserAccount;
import com.soulous.task.StudyTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 【经验日志数据仓库接口：继承 JpaRepository 提供 ExpLog 实体的 CRUD 操作，
 *  并定义自定义查询方法用于按用户、时间范围、关联任务查询日志。】
 */
public interface ExpLogRepository extends JpaRepository<ExpLog, Long> {
    /**
     * 【获取指定用户最近20条经验日志，按创建时间倒序排列。
     *  用于前端展示宠物经验变动历史。】
     */
    List<ExpLog> findTop20ByUserOrderByCreatedAtDesc(UserAccount user);

    /**
     * 【获取指定用户在某个时间点之后的所有经验日志。
     *  可用于统计某段时间内的经验增益情况。】
     */
    List<ExpLog> findByUserAndCreatedAtAfter(UserAccount user, LocalDateTime since);

    /**
     * 【检查指定任务是否有关联的经验日志，用于判断任务是否产生过经验值变动。】
     */
    boolean existsByTask(StudyTask task);

    /**
     * 【删除指定任务关联的所有经验日志，在任务被硬删除时级联清理。
     *  使用 @Modifying 标记为修改操作，需要在事务中执行。】
     */
    @Modifying
    @Transactional
    void deleteByTask(StudyTask task);
}
