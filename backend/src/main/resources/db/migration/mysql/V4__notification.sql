-- 【中文：创建 notification 表 —— 用户通知系统。
--   由服务端业务逻辑创建（AI 审核完成、申诉处理完毕、内容被拦截等），
--   支持按行标记已读（read_at 时间戳），用户可逐条或批量标记已读。
--   包含 ref_type 和 ref_id 用于关联业务实体（如任务提交、申诉等）。】
-- User-facing notifications. Created in-process by services (AI review done,
-- appeal reviewed, content blocked, etc.). Read state is per-row so a user can
-- mark individual items read or hit "mark all read".
CREATE TABLE notification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,           -- 【中文：主键，自增 ID】
    user_id BIGINT NOT NULL,                                  -- 【中文：接收通知的用户 ID】
    type VARCHAR(32) NOT NULL,                                -- 【中文：通知类型（如 AI_REVIEWED、APPEAL_RESULT、CONTENT_BLOCKED 等）】
    title VARCHAR(200) NOT NULL,                              -- 【中文：通知标题，简短概括】
    body VARCHAR(1000),                                       -- 【中文：通知正文内容】
    ref_type VARCHAR(32),                                     -- 【中文：关联实体类型（如 TASK_SUBMISSION、APPEAL 等）】
    ref_id BIGINT,                                            -- 【中文：关联实体 ID，配合 ref_type 定位具体业务对象】
    read_at DATETIME(6),                                      -- 【中文：已读时间，为空表示未读】
    created_at DATETIME(6) NOT NULL,                          -- 【中文：通知创建时间】
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES user_account(id),  -- 【中文：外键关联用户表】
    KEY idx_notification_user_read (user_id, read_at),        -- 【中文：用户+已读复合索引，加速"我的未读通知"查询】
    KEY idx_notification_user_created (user_id, created_at)   -- 【中文：用户+创建时间复合索引，加速按时间排序查询】
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
