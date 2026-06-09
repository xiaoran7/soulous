package com.soulous.pet;

import com.soulous.task.StudyTask;

/**
 * 【宠物成长规则引擎：封装所有与宠物经验值、心情、饱腹感、成长阶段相关的计算逻辑。
 *  采用纯静态方法设计，不持有状态，方便单元测试和复用。
 *  本类是宠物成长系统的核心，所有状态变更（升级、情绪波动、饱腹感变化）都在此完成。】
 */
public final class PetGrowthRules {
    private PetGrowthRules() {}

    /** 【满级等级：到达后不再升级，全部动作解锁。经验条在满级时封顶显示为满。】 */
    public static final int MAX_LEVEL = 30;

    /** 【连续打卡经验倍率上限：streak 越长加成越高，封顶 1.5x】 */
    private static final double STREAK_MULTIPLIER_CAP = 1.5;

    /**
     * 【应用任务完成奖励：当用户完成学习任务后，根据任务经验值和心情系数计算实际获得的经验，
     *  同时更新心情和饱腹感。如果累计经验超过升级阈值则自动升级，并更新成长阶段。
     *  升级后宠物状态变为 EXCITED（兴奋），否则根据经验量判断是 PROUD（自豪）还是 HAPPY（开心）。】
     *
     * @param pet 【宠物实体，方法会直接修改其字段】
     * @param task 【已完成的学习任务，用于获取基础经验值】
     * @param requestedExp 【请求的经验值数量，经过心情系数修正后为实际获得的经验】
     * @return 【成长结果，包含实际经验量、升级前等级、升级后等级、是否升级】
     */
    public static PetGrowthResult applyReward(Pet pet, StudyTask task, int requestedExp) {
        return applyReward(pet, task, requestedExp, 1.0);
    }

    /**
     * 【应用任务完成奖励（带连续打卡倍率重载）：在心情系数之上再叠加 streak 倍率。
     *  streakMultiplier 由调用方依据连续打卡天数算出（见 {@link #streakMultiplier(int)}），
     *  默认 1.0 即与无 streak 行为一致。】
     *
     * @param streakMultiplier 【连续打卡倍率，1.0~1.5】
     */
    public static PetGrowthResult applyReward(Pet pet, StudyTask task, int requestedExp, double streakMultiplier) {
        normalize(pet);
        var previousLevel = pet.level;
        var requested = Math.max(0, requestedExp);
        // 【先按心情加成/减成，再叠加连续打卡倍率】
        var amount = applyStreakMultiplier(applyMoodMultiplier(requested, pet.mood), streakMultiplier);
        var baseExp = Math.max(1, task.baseExp == null ? 20 : task.baseExp);

        pet.currentExp += amount;
        // 【心情变化：经验量达到基础经验80%以上加10，否则加5】
        pet.mood = clamp(pet.mood + moodGain(amount, baseExp));
        // 【饱腹感变化：获得经验时固定增加6点】
        pet.satiety = clamp(pet.satiety + satietyGain(amount));

        var leveledUp = levelUp(pet);

        // 【根据等级更新成长阶段和状态】
        pet.growthStage = stageFor(pet.level);
        pet.status = leveledUp ? PetStatus.EXCITED : rewardStatus(amount, baseExp);
        return new PetGrowthResult(amount, previousLevel, pet.level, leveledUp);
    }

    /**
     * 【应用专注完成奖励：用户完成专注会话后获得经验奖励。
     *  与任务奖励不同，专注奖励固定增加心情+6、饱腹感+4，不依赖任务基础经验。】
     *
     * @param pet 【宠物实体，方法会直接修改其字段】
     * @param requestedExp 【请求的经验值，经过心情系数修正】
     * @return 【成长结果，包含实际经验量、升级前后等级、是否升级】
     */
    public static PetGrowthResult applyFocusReward(Pet pet, int requestedExp) {
        return applyFocusReward(pet, requestedExp, 1.0);
    }

    /**
     * 【应用专注完成奖励（带连续打卡倍率重载）：心情系数之上叠加 streak 倍率，默认 1.0。】
     *
     * @param streakMultiplier 【连续打卡倍率，1.0~1.5】
     */
    public static PetGrowthResult applyFocusReward(Pet pet, int requestedExp, double streakMultiplier) {
        normalize(pet);
        var previousLevel = pet.level;
        var requested = Math.max(0, requestedExp);
        var amount = applyStreakMultiplier(applyMoodMultiplier(requested, pet.mood), streakMultiplier);
        pet.currentExp += amount;
        pet.mood = clamp(pet.mood + 6);
        pet.satiety = clamp(pet.satiety + 4);
        var leveledUp = levelUp(pet);
        pet.growthStage = stageFor(pet.level);
        pet.status = leveledUp ? PetStatus.EXCITED : PetStatus.HAPPY;
        return new PetGrowthResult(amount, previousLevel, pet.level, leveledUp);
    }

    /**
     * 【统一升级逻辑：循环消耗经验升级，到达 {@link #MAX_LEVEL} 后停止。
     *  满级时把当前经验封顶到 nextLevelExp（经验条显示为满），不再累积溢出。】
     *
     * @return 是否发生过升级
     */
    private static boolean levelUp(Pet pet) {
        var leveledUp = false;
        while (pet.level < MAX_LEVEL && pet.currentExp >= pet.nextLevelExp) {
            pet.currentExp -= pet.nextLevelExp;
            pet.level += 1;
            pet.nextLevelExp = expForNextLevel(pet.level);
            leveledUp = true;
        }
        if (pet.level >= MAX_LEVEL) {
            pet.currentExp = Math.min(pet.currentExp, pet.nextLevelExp);
        }
        return leveledUp;
    }

    /**
     * 【连续打卡倍率：streak=1（含未连续）为 1.0，之后每多一天 +0.1，封顶 {@value #STREAK_MULTIPLIER_CAP}。
     *  例如连续 6 天及以上达到 1.5x。】
     *
     * @param streakDays 【连续打卡天数（含今天）】
     */
    public static double streakMultiplier(int streakDays) {
        var extraDays = Math.max(0, streakDays - 1);
        return Math.min(STREAK_MULTIPLIER_CAP, 1.0 + 0.1 * extraDays);
    }

    /** 【对经验量叠加 streak 倍率（倍率夹在 1.0~上限），向上取整四舍五入】 */
    static int applyStreakMultiplier(int amount, double streakMultiplier) {
        if (amount <= 0) return 0;
        var mult = Math.max(1.0, Math.min(STREAK_MULTIPLIER_CAP, streakMultiplier));
        return (int) Math.round(amount * mult);
    }

    /**
     * 【应用"需要更多反馈"惩罚：当任务复核要求补充材料时调用。
     *  心情-4，饱腹感-2，状态变为 SLEEPY（困倦），表示宠物因等待而疲惫。】
     *
     * @param pet 【宠物实体，方法会直接修改其字段】
     */
    public static void applyNeedsMoreFeedback(Pet pet) {
        normalize(pet);
        pet.mood = clamp(pet.mood - 4);
        pet.satiety = clamp(pet.satiety - 2);
        pet.status = PetStatus.SLEEPY;
        pet.growthStage = stageFor(pet.level);
        pet.nextLevelExp = expForNextLevel(pet.level);
    }

    /**
     * 【应用任务开始效果：用户开始执行学习任务时调用。
     *  心情+1（积极投入），饱腹感-1（消耗精力），状态变为 WORKING（工作中）。】
     *
     * @param pet 【宠物实体，方法会直接修改其字段】
     */
    public static void applyTaskStarted(Pet pet) {
        normalize(pet);
        pet.mood = clamp(pet.mood + 1);
        pet.satiety = clamp(pet.satiety - 1);
        pet.status = PetStatus.WORKING;
        pet.growthStage = stageFor(pet.level);
        pet.nextLevelExp = expForNextLevel(pet.level);
    }

    /**
     * 【应用提交复核效果：用户提交任务等待复核时调用。
     *  饱腹感-1（等待消耗），状态变为 REVIEWING（复核中）。】
     *
     * @param pet 【宠物实体，方法会直接修改其字段】
     */
    public static void applySubmittedForReview(Pet pet) {
        normalize(pet);
        pet.satiety = clamp(pet.satiety - 1);
        pet.status = PetStatus.REVIEWING;
        pet.growthStage = stageFor(pet.level);
        pet.nextLevelExp = expForNextLevel(pet.level);
    }

    /**
     * 【应用驳回惩罚：任务复核未通过时调用，是最严厉的惩罚。
     *  心情-10，饱腹感-4，状态变为 SAD（伤心）。】
     *
     * @param pet 【宠物实体，方法会直接修改其字段】
     */
    public static void applyRejectedFeedback(Pet pet) {
        normalize(pet);
        pet.mood = clamp(pet.mood - 10);
        pet.satiety = clamp(pet.satiety - 4);
        pet.status = PetStatus.SAD;
        pet.growthStage = stageFor(pet.level);
        pet.nextLevelExp = expForNextLevel(pet.level);
    }

    /**
     * 【应用断签/久未活跃衰减惩罚：温和机制——只降心情和饱腹感，绝不掉等级或扣经验。
     *  心情 -8、饱腹感 -10；衰减后若心情或饱腹感过低则转 SAD（伤心），否则 SLEEPY（困倦）。
     *  由每日定时任务对久未打卡的用户调用，督促回归而非劝退。】
     *
     * @param pet 【宠物实体，方法会直接修改其字段】
     */
    public static void applyInactivityDecay(Pet pet) {
        normalize(pet);
        pet.mood = clamp(pet.mood - 8);
        pet.satiety = clamp(pet.satiety - 10);
        pet.status = (pet.mood <= 30 || pet.satiety <= 20) ? PetStatus.SAD : PetStatus.SLEEPY;
        pet.growthStage = stageFor(pet.level);
        pet.nextLevelExp = expForNextLevel(pet.level);
    }

    /**
     * 【计算下一级所需经验值：二次曲线 50 + 50·L + 8·L²，让高等级有明显的成长追求感。
     *  Lv1≈108、Lv10≈1350、Lv30≈8750。替代旧的近线性 100+35·L（高等级太平）。】
     *
     * @param level 【当前等级，最小为1】
     * @return 【升到下一级所需的总经验值】
     */
    public static int expForNextLevel(int level) {
        var safeLevel = Math.max(1, level);
        return 50 + 50 * safeLevel + 8 * safeLevel * safeLevel;
    }

    /**
     * 【根据等级确定成长阶段：等级1为 EGG（蛋），2-3为 CHILD（幼年），
     *  4-7为 GROWING（成长中），8及以上为 ADULT（成年）。】
     *
     * @param level 【宠物等级】
     * @return 【对应的成长阶段枚举值】
     */
    public static PetStage stageFor(int level) {
        if (level >= 8) return PetStage.ADULT;
        if (level >= 4) return PetStage.GROWING;
        if (level >= 2) return PetStage.CHILD;
        return PetStage.EGG;
    }

    /**
     * 【根据经验量和基础经验的比较确定奖励后的宠物状态：
     *  经验为0返回 NORMAL，达到基础经验80%以上返回 PROUD（自豪），否则返回 HAPPY（开心）。】
     */
    private static PetStatus rewardStatus(int amount, int baseExp) {
        if (amount <= 0) return PetStatus.NORMAL;
        return amount >= Math.ceil(baseExp * 0.8) ? PetStatus.PROUD : PetStatus.HAPPY;
    }

    /**
     * 【心情经验加成系数计算：心情>=80时经验×1.2（开心加成），心情<=30时经验×0.8（低落减成），
     *  否则×1.0（正常）。这激励用户保持宠物好心情以获得更多经验。】
     *
     * @param amount 【原始经验值】
     * @param mood 【当前心情值，范围0-100，默认80】
     * @return 【经过心情系数修正后的经验值】
     */
    static int applyMoodMultiplier(int amount, Integer mood) {
        if (amount <= 0) return 0;
        int m = mood == null ? 80 : Math.max(0, Math.min(100, mood));
        double mult = m >= 80 ? 1.2 : (m <= 30 ? 0.8 : 1.0);
        return (int) Math.round(amount * mult);
    }

    /**
     * 【计算任务奖励带来的心情增益：经验量达到基础经验80%以上加10，否则加5。
     *  高质量完成任务能获得更大的心情提升。】
     */
    private static int moodGain(int amount, int baseExp) {
        if (amount <= 0) return 0;
        return amount >= Math.ceil(baseExp * 0.8) ? 10 : 5;
    }

    /**
     * 【计算任务奖励带来的饱腹感增益：有经验收入时固定+6，无经验时为0。】
     */
    private static int satietyGain(int amount) {
        return amount <= 0 ? 0 : 6;
    }

    /**
     * 【宠物属性归一化：确保所有字段都有合法的默认值，防止空指针和异常状态。
     *  等级最小为1，经验最小为0，心情和饱腹感默认80，成长阶段和状态也有默认值。
     *  在每次成长规则计算前调用，保证计算安全。】
     */
    private static void normalize(Pet pet) {
        pet.level = pet.level == null || pet.level < 1 ? 1 : pet.level;
        pet.currentExp = pet.currentExp == null || pet.currentExp < 0 ? 0 : pet.currentExp;
        pet.nextLevelExp = pet.nextLevelExp == null || pet.nextLevelExp < 1 ? expForNextLevel(pet.level) : pet.nextLevelExp;
        pet.mood = pet.mood == null ? 80 : clamp(pet.mood);
        pet.satiety = pet.satiety == null ? 80 : clamp(pet.satiety);
        pet.growthStage = pet.growthStage == null ? stageFor(pet.level) : pet.growthStage;
        pet.status = pet.status == null ? PetStatus.NORMAL : pet.status;
    }

    /**
     * 【将数值限制在0-100范围内，用于心情和饱腹感的边界约束。】
     */
    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
