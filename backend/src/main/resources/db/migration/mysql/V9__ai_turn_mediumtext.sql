-- 【放大 AI 会话轮次正文列：TEXT → MEDIUMTEXT】
-- 自习室 AI 拆解对话新增「上传文件喂给 AI」能力：前端把 md/pdf/txt 提取的文本（上限 30,000 字符）
-- 拼进消息正文，作为 USER 轮次持久化。MySQL 的 TEXT 上限约 64KB 字节，而 30,000 个中文字符
-- 按 UTF-8 计可达 ~90KB，会溢出导致插入失败。改为 MEDIUMTEXT（上限 16MB）留足余量。
-- 注：H2（本地/测试）的 TEXT 即 CLOB，容量足够，无需对应迁移。
ALTER TABLE session_turn MODIFY COLUMN content MEDIUMTEXT;
