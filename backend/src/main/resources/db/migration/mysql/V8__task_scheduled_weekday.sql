-- 【中文：为 study_task 表新增 scheduled_weekday 列（MySQL 版本）。
--   用途：AI 基于课表排出的学习任务会带"建议安排在周几"（1-7，周一=1）。
--   可空——手动创建、与课表无关的任务不填。】
-- Adds scheduled_weekday (1-7, Mon=1): which weekday a timetable-aware AI plan suggests this task on.
ALTER TABLE study_task ADD COLUMN scheduled_weekday INT;
