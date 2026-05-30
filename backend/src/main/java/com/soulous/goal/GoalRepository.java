package com.soulous.goal;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【目标数据仓库接口：继承 JpaRepository 提供 Goal 实体的 CRUD 操作，
 *  并定义按用户和状态排序查询的自定义方法。】
 */
public interface GoalRepository extends JpaRepository<Goal, Long> {
    /**
     * 【获取指定用户的所有目标，按更新时间倒序排列（最近更新的在前）。】
     */
    List<Goal> findByUserOrderByUpdatedAtDesc(UserAccount user);

    /**
     * 【获取指定用户中特定状态的目标，按更新时间倒序排列。
     *  可用于筛选活跃、暂停、已完成等状态的目标。】
     */
    List<Goal> findByUserAndStatusOrderByUpdatedAtDesc(UserAccount user, GoalStatus status);

    /**
     * 【获取指定用户中排除特定状态的目标，按更新时间倒序排列。
     *  例如排除 ARCHIVED 状态以获取非归档目标。】
     */
    List<Goal> findByUserAndStatusNotOrderByUpdatedAtDesc(UserAccount user, GoalStatus excluded);
}
