-- 【中文：为 task_submission 表的 status 列添加 AI_REVIEWING 状态（H2 版本）。
--   H2 的 enum 列类型不支持 ALTER ... ADD VALUE，需要通过四步操作扩展：
--   1. 将列临时改为 VARCHAR(32)；2. 重新设为包含新值的 ENUM。
--   所有现有数据行会被完整保留。】
-- Adds the transient AI_REVIEWING status set by the async review pipeline.
-- H2's enum() column type doesn't allow ALTER ... ADD VALUE; we widen by recreating
-- the column constraint via a 4-step dance (add temp varchar, copy, drop, rename).
-- All existing rows are preserved verbatim.

-- 【中文：第一步 —— 将 status 列临时改为 VARCHAR(32)，解除 ENUM 约束】
ALTER TABLE task_submission ALTER COLUMN status VARCHAR(32);
-- 【中文：第二步 —— 重新设为包含 AI_REVIEWING 的 ENUM 类型】
ALTER TABLE task_submission ALTER COLUMN status
    SET DATA TYPE ENUM(
        'PENDING',
        'AI_REVIEWING',
        'AI_APPROVED',
        'AI_REJECTED',
        'NEED_MORE',
        'MANUAL_APPROVED',
        'MANUAL_REJECTED',
        'MODERATION_BLOCKED'
    );
