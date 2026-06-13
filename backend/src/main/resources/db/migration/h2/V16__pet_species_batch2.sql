-- 【中文：宠物市场第二批上架（H2 版本）—— 13 只专属形象宠物（hatch 生成的 8×9 sprite atlas，
--   素材在 frontend/public/pets/<slug>/）。同时下架 V14 里复用飞雪占位图的三只占位款
--   （mochi/ember/aurora），但已被用户拥有的保留，避免破坏 owned_pet 外键。
--   id 延续 V14 的显式主键风格（identity 计数器不随显式插入推进，混用会撞主键）。】
-- Pet market batch 2: 13 species with dedicated sprite atlases; delist unowned placeholder species.

DELETE FROM pet_species
 WHERE slug IN ('mochi', 'ember', 'aurora')
   AND id NOT IN (SELECT DISTINCT species_id FROM owned_pet WHERE species_id IS NOT NULL);

INSERT INTO pet_species (id, slug, name, rarity, price, starter, sprite_path, description, sort_order) VALUES
 (5,  'clawd',          'Clawd',    'COMMON', 0,   TRUE,  '/pets/clawd/spritesheet.webp',          '像素小螃蟹，免费入门好伙伴', 5),
 (6,  'guga',           '咕嘎',     'COMMON', 80,  FALSE, '/pets/guga/spritesheet.webp',           '圆滚滚的企鹅连帽衫女孩', 6),
 (7,  'doro',           'Doro',     'COMMON', 80,  FALSE, '/pets/doro/spritesheet.webp',           '粉发紫瞳的迷你小白团子', 7),
 (8,  'bellylaugh',     '奶龙',     'COMMON', 100, FALSE, '/pets/bellylaugh/spritesheet.webp',     '圆圆黄黄，爱捧腹大笑的小奶龙', 8),
 (9,  'ikun',           '鸡哥ikun', 'RARE',   150, FALSE, '/pets/ikun/spritesheet.webp',           '背着龟壳书包的鸭鸭骑士，律保安', 9),
 (10, 'gemma',          'Gemma',    'RARE',   150, FALSE, '/pets/gemma/spritesheet.webp',          '额间宝石的瞌睡修女猫', 10),
 (11, 'lumiboba1',      'LumiBoba', 'RARE',   150, FALSE, '/pets/lumiboba1/spritesheet.webp',      '白发猫耳的奶茶系桌面伙伴', 11),
 (12, 'iroha-doctoral', '彩叶博士', 'RARE',   180, FALSE, '/pets/iroha-doctoral/spritesheet.webp', '穿白大褂的科研系博士生，陪你卷', 12),
 (13, 'yume-boundary',  'Yume',     'RARE',   180, FALSE, '/pets/yume-boundary/spritesheet.webp',  '优雅好奇的边界探索研究伙伴', 13),
 (14, 'tianyi',         '洛天依',   'EPIC',   300, FALSE, '/pets/tianyi/spritesheet.webp',         '音之精灵相伴的虚拟歌手', 14),
 (15, 'saiyan-prince',  '赛亚王子', 'EPIC',   300, FALSE, '/pets/saiyan-prince/spritesheet.webp',  '骄傲的战斗王子，状态切换即变身', 15),
 (16, 'frieren',        '芙莉莲',   'EPIC',   350, FALSE, '/pets/frieren/spritesheet.webp',        '沉静好奇的精灵魔法使', 16),
 (17, 'xilian',         '昔涟',     'EPIC',   350, FALSE, '/pets/xilian/spritesheet.webp',         '承载往昔涟漪的记忆命途少女', 17);
