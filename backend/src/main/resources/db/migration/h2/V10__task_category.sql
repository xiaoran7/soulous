-- 【中文：为 study_task 表新增 category 列（H2 版本）。
--   用途：任务的「大分类」，更高一层的主题分组，与 AI 拆解的对话分类共用命名。
--   从 AI 拆解落地的任务会自动写入所在对话的分类名；手动任务可自定义。可空。
--   结构与 mysql 版本一致。】
-- Adds category (high-level grouping shared with AI chat categories) to study_task.
ALTER TABLE study_task ADD COLUMN category VARCHAR(64);
