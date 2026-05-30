-- 【中文：移除 study_task 表的遗留 priority（优先级）列。
--   该字段在 StudyTask 实体重构时已从代码中删除（commit 02f7bb2），但 Hibernate ddl-auto=update
--   不会自动删除列，导致数据库中仍残留此列。本迁移脚本最终将其清理。
--   MySQL 8.0.23 之前不支持 DROP COLUMN IF EXISTS，因此通过存储过程动态判断列是否存在后再执行。】
-- Drop legacy `priority` column on study_task. The field was removed from StudyTask entity in
-- 02f7bb2 ("refactor: tighten task model"), but Hibernate ddl-auto=update never deletes columns,
-- so existing databases still carry the dead column. This migration finally removes it.
-- MySQL lacks IF EXISTS for DROP COLUMN before 8.0.23; gate with a stored procedure.
SET @stmt := IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'study_task' AND COLUMN_NAME = 'priority'),
  'ALTER TABLE study_task DROP COLUMN priority',
  'SELECT 1');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
