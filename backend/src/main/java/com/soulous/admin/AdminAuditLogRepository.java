package com.soulous.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 【管理员审核日志的数据访问层接口，继承 JpaRepository 提供基本 CRUD 操作。
 * 定义了按时间排序查询最近记录和按目标类型/ID 查询的自定义方法。】
 */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    /**
     * 【查询最近 100 条审核日志，按创建时间倒序排列。
     * 用于管理后台的审核历史概览。】
     *
     * @return 【最近 100 条审核日志列表】
     */
    List<AdminAuditLog> findTop100ByOrderByCreatedAtDesc();

    /**
     * 【按目标类型和目标ID查询审核日志，按创建时间倒序排列。
     * 用于查看特定提交或申诉的完整审核历史。】
     *
     * @param targetType 【审核目标类型枚举】
     * @param targetId   【目标记录ID】
     * @return 【匹配的审核日志列表】
     */
    List<AdminAuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(AuditTargetType targetType, Long targetId);
}
