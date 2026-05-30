-- 【中文：移除 study_task 表的遗留 priority（优先级）列（H2 版本）。
--   H2 原生支持 DROP COLUMN IF EXISTS 语法，无需像 MySQL 那样使用存储过程。】
-- Drop legacy `priority` column on study_task. The field was removed from StudyTask entity in
-- 02f7bb2 ("refactor: tighten task model"), but Hibernate ddl-auto=update never deletes columns,
-- so existing databases still carry the dead column. This migration finally removes it.
ALTER TABLE study_task DROP COLUMN IF EXISTS priority;
