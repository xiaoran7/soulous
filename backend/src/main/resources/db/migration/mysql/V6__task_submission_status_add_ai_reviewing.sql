-- 【中文：为 task_submission 表的 status 枚举列添加 AI_REVIEWING 状态。
--   异步 AI 审核流水线在处理提交时，需要一个中间状态标记"正在审核中"，
--   以便前端展示审核进度和防止重复提交。
--   MySQL 允许就地扩展枚举（只要不删除已有值），无需重建表。】
-- Adds the transient AI_REVIEWING status set by the async review pipeline.
-- MySQL allows in-place enum widening as long as no existing value is removed.

-- 【中文：重新定义 status 枚举列，新增 AI_REVIEWING 值（位于 PENDING 之后）。
--   枚举值顺序：PENDING → AI_REVIEWING → AI_APPROVED → AI_REJECTED → NEED_MORE → MANUAL_APPROVED → MANUAL_REJECTED → MODERATION_BLOCKED】
ALTER TABLE task_submission MODIFY COLUMN status
    ENUM(
        'PENDING',
        'AI_REVIEWING',
        'AI_APPROVED',
        'AI_REJECTED',
        'NEED_MORE',
        'MANUAL_APPROVED',
        'MANUAL_REJECTED',
        'MODERATION_BLOCKED'
    );
