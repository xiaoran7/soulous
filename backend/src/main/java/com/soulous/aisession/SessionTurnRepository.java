package com.soulous.aisession;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 【会话轮次仓储接口：提供 SessionTurn 实体的数据库查询与删除能力】
 *
 * <p>继承 JpaRepository 获得基础 CRUD，额外定义按会话查询轮次和批量删除的方法。</p>
 */
public interface SessionTurnRepository extends JpaRepository<SessionTurn, Long> {

    /**
     * 【按会话查询全部轮次（升序）：返回指定会话的所有对话轮次，按 idx 从小到大排列】
     *
     * @param session 【会话实体】
     * @return 【轮次列表，按 idx 升序】
     */
    List<SessionTurn> findBySessionOrderByIdxAsc(PlanningSession session);

    /**
     * 【按会话查询最近 12 条轮次（降序）：用于滚动摘要和最近窗口加载，取最新 12 条后反转】
     *
     * @param session 【会话实体】
     * @return 【最近 12 条轮次，按 idx 降序排列】
     */
    List<SessionTurn> findTop12BySessionOrderByIdxDesc(PlanningSession session);

    /**
     * 【按会话批量删除轮次：删除指定会话下的所有对话记录，用于会话删除和清理任务】
     *
     * @param session 【会话实体】
     */
    @Modifying
    @Transactional
    void deleteBySession(PlanningSession session);
}
