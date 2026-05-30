-- 【中文：创建 audit_log 表 —— 通用安全审计日志。
--   记录面向用户的安全事件（登录成功/失败、登出、密码修改、refresh token 重放攻击）
--   和管理员生命周期操作（用户创建、角色变更）。
--   与 admin_audit_log（审核队列操作）和 moderation_log（内容过滤命中）有意分离——
--   后两者有更丰富的领域专属 schema，保持独立设计。】
-- Generic audit log. Captures user-facing security events (login success/failure,
-- logout, password change, refresh-token replay) and admin lifecycle actions
-- (user creation, role updates). Separate from admin_audit_log (review queue
-- actions) and moderation_log (content filter hits) on purpose — those two tables
-- have richer per-domain schemas and are kept independent.
CREATE TABLE audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,           -- 【中文：主键，自增 ID】
    actor_user_id BIGINT,                                     -- 【中文：操作者用户 ID（系统操作时可为空）】
    actor_username VARCHAR(64),                               -- 【中文：操作者用户名（冗余存储，避免 JOIN 查询）】
    actor_role VARCHAR(32),                                   -- 【中文：操作者角色（ADMIN/USER）】
    action VARCHAR(64) NOT NULL,                              -- 【中文：操作类型（如 LOGIN_SUCCESS、LOGOUT、PASSWORD_CHANGE 等）】
    target_type VARCHAR(32),                                  -- 【中文：目标实体类型（如 USER、REFRESH_TOKEN 等）】
    target_id BIGINT,                                         -- 【中文：目标实体 ID】
    ip VARCHAR(64),                                           -- 【中文：操作者 IP 地址】
    user_agent VARCHAR(255),                                  -- 【中文：操作者 User-Agent】
    success BOOLEAN NOT NULL,                                 -- 【中文：操作是否成功】
    details TEXT,                                             -- 【中文：额外详情（如失败原因、变更前后值等）】
    created_at DATETIME(6) NOT NULL,                          -- 【中文：操作时间】
    KEY idx_audit_log_actor_created (actor_user_id, created_at),  -- 【中文：操作者+时间复合索引，支持"查看某用户的操作历史"】
    KEY idx_audit_log_action_created (action, created_at),        -- 【中文：操作类型+时间复合索引，支持"按事件类型筛选"】
    KEY idx_audit_log_target (target_type, target_id)              -- 【中文：目标类型+ID 复合索引，支持"查看某对象的操作记录"】
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
