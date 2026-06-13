-- 【中文：AI 长期记忆开关（H2 版本）—— user_account 增加 ai_memory_enabled 列。
--   默认开启；关闭后该用户的 RAG 索引与检索一律空操作。结构与 mysql 版本保持一致。】
-- AI long-term memory toggle: per-user opt-out for RAG index/retrieval.
ALTER TABLE user_account ADD COLUMN ai_memory_enabled BOOLEAN DEFAULT TRUE NOT NULL;
