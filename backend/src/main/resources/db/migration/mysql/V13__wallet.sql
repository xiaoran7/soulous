-- 【中文：金币钱包（MySQL 版本）—— user_account 增加 coin_balance 余额列，新增 coin_ledger 流水表。
--   金币账号级共享，完成任务/打卡/专注赚取，用于宠物市场购买。】
-- Coin wallet: balance column on user_account + coin_ledger table.
ALTER TABLE user_account ADD COLUMN coin_balance INT NOT NULL DEFAULT 0;

CREATE TABLE coin_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,            -- 【中文：主键，自增 ID】
    user_id BIGINT NOT NULL,                                  -- 【中文：关联用户 ID】
    amount INT NOT NULL,                                      -- 【中文：变动金额，正=入账/负=出账】
    balance_after INT NOT NULL,                               -- 【中文：变动后余额快照】
    source VARCHAR(40),                                       -- 【中文：来源 TASK/FOCUS/CHECKIN/PURCHASE/ADJUST】
    ref_type VARCHAR(40),                                     -- 【中文：关联实体类型】
    ref_id BIGINT,                                            -- 【中文：关联实体 ID】
    reason VARCHAR(200),                                      -- 【中文：变动说明】
    created_at DATETIME(6) NOT NULL,                          -- 【中文：创建时间】
    CONSTRAINT fk_coin_ledger_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    KEY idx_coin_ledger_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
