-- 【中文：创建 refresh_token 表 —— 实现双令牌认证方案。
--   访问令牌（Access JWT）有效期短（约1小时），刷新令牌有效期长（30天），
--   以 SHA-256 哈希存储（即使数据库泄露也无法直接使用原令牌），
--   支持服务端撤销（登出/全部登出）。包含用户代理和 IP 地址用于安全审计。】
-- Refresh tokens for the dual-token auth scheme. Access JWTs stay short (~1h);
-- refresh tokens live for 30d, are stored as SHA-256 hash (so a DB leak doesn't
-- yield usable tokens), and can be revoked server-side (logout / logout-all).
CREATE TABLE refresh_token (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,           -- 【中文：主键，自增 ID】
    user_id BIGINT NOT NULL,                                  -- 【中文：关联用户 ID】
    token_hash VARCHAR(64) NOT NULL,                          -- 【中文：refresh token 的 SHA-256 哈希值（64 字符十六进制）】
    expires_at DATETIME(6) NOT NULL,                          -- 【中文：令牌过期时间，默认 30 天后】
    revoked_at DATETIME(6),                                   -- 【中文：令牌撤销时间（登出时设置），为空表示有效】
    created_at DATETIME(6) NOT NULL,                          -- 【中文：令牌创建时间】
    last_used_at DATETIME(6),                                 -- 【中文：令牌最后使用时间，用于检测长时间未使用的令牌】
    user_agent VARCHAR(255),                                  -- 【中文：客户端 User-Agent，用于安全审计和多设备管理】
    ip VARCHAR(64),                                           -- 【中文：客户端 IP 地址，用于安全审计】
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES user_account(id),  -- 【中文：外键关联用户表】
    UNIQUE KEY idx_refresh_token_hash (token_hash),           -- 【中文：令牌哈希唯一索引，用于快速查找和防重复】
    KEY idx_refresh_token_user (user_id)                      -- 【中文：用户 ID 索引，用于查询用户的所有令牌（如全部登出）】
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
