-- 【中文：伴侣昵称（H2 版本）—— user_account 增加 companion_nickname 列。
--   全局、跨宠物共享的称呼：换出战宠物/换品种都用这一个名字，与每只宠物自带的品种名形成「昵称 · 名称」双段。
--   旧数据回填：优先用账号昵称，空则用用户名，保证老用户登录即有一个合理默认。结构与 mysql 版本保持一致。】
-- Companion nickname: global pet name that follows the user across pets.
ALTER TABLE user_account ADD COLUMN companion_nickname VARCHAR(32);

UPDATE user_account
   SET companion_nickname = COALESCE(NULLIF(TRIM(nickname), ''), username)
 WHERE companion_nickname IS NULL;
