package com.soulous.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 【审计日志的数据访问层接口，继承 JpaRepository 提供基本 CRUD 操作。
 * 定义了带多条件筛选的分页查询方法，所有筛选参数均可选。】
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 【多条件筛选的审计日志分页查询方法。
     * 覆盖管理端点接受的所有筛选组合，每个参数都是可选的——
     * 传入 null 时该条件自动变为 TRUE（即不过滤），
     * 调用方无需在五种不同的查询方法之间切换。】
     *
     * <p>Single filtered query covering every combination the admin endpoint accepts.
     * Each parameter is optional — a null collapses that predicate to TRUE so the
     * caller doesn't have to switch between five different finder methods.</p>
     *
     * @param action      【操作类型，为 null 时不过滤】
     * @param actorUserId 【操作者用户ID，为 null 时不过滤】
     * @param from        【起始时间，为 null 时不过滤】
     * @param to          【结束时间，为 null 时不过滤】
     * @param pageable    【分页参数】
     * @return 【分页结果，按创建时间倒序、ID 倒序排列】
     */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE (:action IS NULL OR a.action = :action)
              AND (:actorUserId IS NULL OR a.actorUserId = :actorUserId)
              AND (:from IS NULL OR a.createdAt >= :from)
              AND (:to IS NULL OR a.createdAt <= :to)
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    Page<AuditLog> search(@Param("action") String action,
                          @Param("actorUserId") Long actorUserId,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          Pageable pageable);
}
