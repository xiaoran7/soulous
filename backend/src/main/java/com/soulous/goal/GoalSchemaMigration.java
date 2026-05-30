package com.soulous.goal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-off schema patch: adds ARCHIVED to the goal.status enum in H2 (local dev).
 * In MySQL (prod) the DBA runs a proper migration; this class is excluded from
 * the "prod" profile so it never fires in production.
 *
 * Safe to run on every startup — MODIFY COLUMN is idempotent in both H2 and MySQL.
 *
 * 【目标表结构迁移组件：在本地开发环境（H2 数据库）启动时，向 goal.status 枚举列添加 ARCHIVED 值。
 *  生产环境（MySQL）由 DBA 执行正式迁移脚本，本类通过 @Profile("!prod") 排除在生产环境之外。
 *  使用 @EventListener(ApplicationReadyEvent.class) 在应用完全启动后执行，
 *  确保数据源和 JdbcTemplate 已就绪。MODIFY COLUMN 操作是幂等的，重复执行不会报错。】
 */
@Component
@Profile("!prod")
public class GoalSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(GoalSchemaMigration.class);
    /** 【Spring JDBC 模板，用于执行原生 SQL 语句】 */
    private final JdbcTemplate jdbc;

    public GoalSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 【应用启动后执行的 schema 补丁：修改 goal 表的 status 列枚举定义，
     *  添加 ARCHIVED 值。如果列定义已经是正确的则捕获异常并记录 debug 日志，不影响启动。】
     */
    @EventListener(ApplicationReadyEvent.class)
    public void patchGoalStatusEnum() {
        try {
            jdbc.execute(
                "ALTER TABLE goal MODIFY COLUMN status " +
                "ENUM('ACTIVE','PAUSED','ACHIEVED','ABANDONED','ARCHIVED') NOT NULL"
            );
            log.debug("GoalSchemaMigration: goal.status column patched with ARCHIVED value");
        } catch (Exception e) {
            // Column may already have the correct definition — log at debug level only
            // 【列定义可能已经是正确的，仅记录 debug 级别日志，不影响启动流程】
            log.debug("GoalSchemaMigration: patch skipped or already applied: {}", e.getMessage());
        }
    }
}
