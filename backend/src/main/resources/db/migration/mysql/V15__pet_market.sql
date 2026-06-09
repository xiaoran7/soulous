-- 【中文：宠物市场（MySQL 版本）—— 品种目录 + 用户拥有多只。非破坏式：保留旧 pet 表，
--   新建 owned_pet 并迁移现有宠物（active=true，默认品种 feixue）。每只宠物独立等级/经验。】
-- Pet market: species catalog + per-user owned pets (non-destructive; old pet table left intact).

CREATE TABLE pet_species (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    slug VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    rarity VARCHAR(32),
    price INT NOT NULL,
    starter BOOLEAN NOT NULL,
    sprite_path VARCHAR(255),
    description VARCHAR(255),
    sort_order INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO pet_species (id, slug, name, rarity, price, starter, sprite_path, description, sort_order) VALUES
 (1, 'feixue', '飞雪', 'COMMON', 0,   TRUE,  '/pets/feixue/spritesheet.webp', '冷静专注的入门小伙伴', 1),
 (2, 'mochi',  '麻糬', 'COMMON', 0,   TRUE,  '/pets/feixue/spritesheet.webp', '软糯黏人的入门小伙伴', 2),
 (3, 'ember',  '炭炭', 'RARE',   120, FALSE, '/pets/feixue/spritesheet.webp', '热情似火，督促你别拖延', 3),
 (4, 'aurora', '极光', 'EPIC',   300, FALSE, '/pets/feixue/spritesheet.webp', '稀有梦幻，陪你冲刺高目标', 4);

CREATE TABLE owned_pet (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    species_id BIGINT,
    name VARCHAR(255),
    avatar_url VARCHAR(255),
    level INT,
    current_exp INT,
    next_level_exp INT,
    mood INT,
    satiety INT,
    growth_stage VARCHAR(32),
    status VARCHAR(32),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    acquired_at DATETIME(6),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    CONSTRAINT fk_owned_pet_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_owned_pet_species FOREIGN KEY (species_id) REFERENCES pet_species(id),
    KEY idx_owned_pet_user (user_id),
    KEY idx_owned_pet_user_active (user_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO owned_pet (user_id, species_id, name, avatar_url, level, current_exp, next_level_exp, mood, satiety, growth_stage, status, active, acquired_at, created_at, updated_at)
SELECT user_id, 1, name, avatar_url, level, current_exp, next_level_exp, mood, satiety, growth_stage, status, TRUE, created_at, created_at, updated_at
FROM pet;
