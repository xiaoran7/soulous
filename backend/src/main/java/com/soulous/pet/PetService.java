package com.soulous.pet;

import com.soulous.auth.UserAccount;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.task.StudyTask;
import com.soulous.task.TaskSubmission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 【宠物业务服务：封装所有宠物相关的业务逻辑，包括经验值增减、状态变更、喂食、
 *  重命名、设置头像等操作。所有写操作使用 @Transactional 保证数据一致性。
 *  依赖 PetGrowthRules 进行成长计算，依赖 ExpLogRepository 记录经验变动日志。】
 */
@Service
public class PetService {
    /** 【宠物数据仓库，负责宠物实体的 CRUD 操作】 */
    private final PetRepository pets;
    /** 【经验日志数据仓库，记录所有经验值变动历史】 */
    private final ExpLogRepository expLogs;
    /** 【宠物品种目录仓库（市场）】 */
    private final PetSpeciesRepository species;
    /** 【金币服务，购买宠物时扣费】 */
    private final com.soulous.wallet.CoinService coins;

    PetService(PetRepository pets, ExpLogRepository expLogs, PetSpeciesRepository species,
               com.soulous.wallet.CoinService coins) {
        this.pets = pets;
        this.expLogs = expLogs;
        this.species = species;
        this.coins = coins;
    }

    /**
     * 【获取当前用户的出战宠物，不存在则抛出 NotFoundException。
     *  用于喂食/重命名/设头像等「必须有宠物」的操作。】
     *
     * @param user 【当前登录用户】
     * @return 【出战宠物实体】
     * @throws NotFoundException 【没有出战宠物时抛出】
     */
    public Pet get(UserAccount user) {
        return pets.findByUserAndActiveTrue(user).orElseThrow(() -> new NotFoundException("Pet not found"));
    }

    /**
     * 【获取出战宠物或 null：用于奖励路径与对外展示。
     *  新用户尚未领养时返回 null，奖励据此安全跳过（不抛错）。】
     */
    public Pet getActiveOrNull(UserAccount user) {
        return pets.findByUserAndActiveTrue(user).orElse(null);
    }

    /**
     * 【为宠物增加经验值（任务完成奖励）：调用成长规则引擎计算实际经验，
     *  更新宠物状态，并记录 EXP_GAINED 类型的经验日志。】
     *
     * @param user 【当前登录用户】
     * @param task 【已完成的学习任务】
     * @param submission 【任务提交记录】
     * @param amount 【请求的经验值数量】
     * @param reason 【经验变动原因说明】
     * @return 【更新后的宠物实体】
     */
    @Transactional
    public Pet addExp(UserAccount user, StudyTask task, TaskSubmission submission, int amount, String reason) {
        var pet = getActiveOrNull(user);
        if (pet == null) return null;
        var result = PetGrowthRules.applyReward(pet, task, amount);
        pet.updatedAt = LocalDateTime.now();
        pets.save(pet);
        saveLog(user, task, submission, result.expAmount(), "EXP_GAINED", reason);
        return pet;
    }

    /**
     * 【为宠物增加专注经验值（专注完成奖励）：调用专注奖励规则计算经验，
     *  记录 FOCUS_EXP 类型的经验日志。专注奖励不关联任务和提交记录。】
     *
     * @param user 【当前登录用户】
     * @param amount 【请求的经验值数量】
     * @param reason 【经验变动原因说明】
     * @return 【更新后的宠物实体】
     */
    @Transactional
    public Pet addFocusExp(UserAccount user, int amount, String reason) {
        var pet = getActiveOrNull(user);
        if (pet == null) return null;
        var result = PetGrowthRules.applyFocusReward(pet, amount);
        pet.updatedAt = LocalDateTime.now();
        pets.save(pet);
        saveLog(user, null, null, result.expAmount(), "FOCUS_EXP", reason);
        return pet;
    }

    /**
     * 【每日打卡奖励：给出战宠物发放经验，叠加连续打卡 streak 倍率，记录 CHECKIN_EXP 日志。】
     *
     * @param user             【当前登录用户】
     * @param amount           【基础经验值】
     * @param streakMultiplier 【连续打卡倍率，见 PetGrowthRules.streakMultiplier】
     * @param reason           【经验变动原因说明】
     * @return 【更新后的宠物实体】
     */
    @Transactional
    public Pet addCheckinExp(UserAccount user, int amount, double streakMultiplier, String reason) {
        var pet = getActiveOrNull(user);
        if (pet == null) return null;
        var result = PetGrowthRules.applyFocusReward(pet, amount, streakMultiplier);
        pet.updatedAt = LocalDateTime.now();
        pets.save(pet);
        saveLog(user, null, null, result.expAmount(), "CHECKIN_EXP", reason);
        return pet;
    }

    /**
     * 【标记任务开始：宠物进入工作状态，记录 TASK_STARTED 类型日志。
     *  心情+1，饱腹感-1，状态变为 WORKING。】
     *
     * @param user 【当前登录用户】
     * @param task 【开始执行的学习任务】
     * @return 【更新后的宠物实体】
     */
    @Transactional
    public Pet markTaskStarted(UserAccount user, StudyTask task) {
        var pet = getActiveOrNull(user);
        if (pet == null) return null;
        PetGrowthRules.applyTaskStarted(pet);
        pet.updatedAt = LocalDateTime.now();
        pets.save(pet);
        saveLog(user, task, null, 0, "TASK_STARTED", "开始任务：" + safe(task.title));
        return pet;
    }

    /**
     * 【标记任务已提交复核：宠物进入复核中状态，记录 SUBMITTED_FOR_REVIEW 日志。
     *  饱腹感-1，状态变为 REVIEWING。】
     *
     * @param user 【当前登录用户】
     * @param task 【已提交的学习任务】
     * @param submission 【任务提交记录】
     * @return 【更新后的宠物实体】
     */
    @Transactional
    public Pet markSubmittedForReview(UserAccount user, StudyTask task, TaskSubmission submission) {
        var pet = getActiveOrNull(user);
        if (pet == null) return null;
        PetGrowthRules.applySubmittedForReview(pet);
        pet.updatedAt = LocalDateTime.now();
        pets.save(pet);
        saveLog(user, task, submission, 0, "SUBMITTED_FOR_REVIEW", "提交复核：" + safe(task.title));
        return pet;
    }

    /**
     * 【标记需要补充材料：复核要求用户补充更多内容时调用，记录 NEEDS_MORE 日志。
     *  心情-4，饱腹感-2，状态变为 SLEEPY。】
     *
     * @param user 【当前登录用户】
     * @param task 【需要补充的学习任务】
     * @param submission 【任务提交记录】
     * @param reason 【需要补充的原因】
     * @return 【更新后的宠物实体】
     */
    @Transactional
    public Pet markNeedsMore(UserAccount user, StudyTask task, TaskSubmission submission, String reason) {
        var pet = getActiveOrNull(user);
        if (pet == null) return null;
        PetGrowthRules.applyNeedsMoreFeedback(pet);
        pet.updatedAt = LocalDateTime.now();
        pets.save(pet);
        saveLog(user, task, submission, 0, "NEEDS_MORE", "需要补充：" + safe(reason));
        return pet;
    }

    /**
     * 【标记任务被驳回：复核未通过时调用，是最严厉的惩罚，记录 REJECTED 日志。
     *  心情-10，饱腹感-4，状态变为 SAD。】
     *
     * @param user 【当前登录用户】
     * @param task 【被驳回的学习任务】
     * @param submission 【任务提交记录】
     * @param reason 【驳回原因】
     * @return 【更新后的宠物实体】
     */
    @Transactional
    public Pet markRejected(UserAccount user, StudyTask task, TaskSubmission submission, String reason) {
        var pet = getActiveOrNull(user);
        if (pet == null) return null;
        PetGrowthRules.applyRejectedFeedback(pet);
        pet.updatedAt = LocalDateTime.now();
        pets.save(pet);
        saveLog(user, task, submission, 0, "REJECTED", "凭证未通过：" + safe(reason));
        return pet;
    }

    /**
     * 【重命名宠物：名称为空或空白时恢复为用户名，名称超过32字符自动截断。】
     *
     * @param user 【当前登录用户】
     * @param newName 【新名称】
     * @return 【更新后的宠物实体】
     */
    @Transactional
    public Pet rename(UserAccount user, String newName) {
        var pet = getActiveOrNull(user);
        if (pet == null) return null;
        var trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isBlank()) {
            pet.name = user.username;
        } else {
            pet.name = trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
        }
        pet.updatedAt = LocalDateTime.now();
        return pets.save(pet);
    }

    /**
     * 【设置宠物头像 URL：空白字符串视为 null（清除头像）。】
     *
     * @param user 【当前登录用户】
     * @param avatarUrl 【头像 URL，null 表示清除头像】
     * @return 【更新后的宠物实体】
     */
    @Transactional
    public Pet setAvatar(UserAccount user, String avatarUrl) {
        var pet = getActiveOrNull(user);
        if (pet == null) return null;
        if (avatarUrl != null && avatarUrl.isBlank()) avatarUrl = null;
        pet.avatarUrl = avatarUrl;
        pet.updatedAt = LocalDateTime.now();
        return pets.save(pet);
    }

    /**
     * 【喂食宠物：饱腹感+20（上限100），心情+5（上限100）。
     *  喂食是用户主动关心宠物的互动方式，有助于保持高心情获得经验加成。】
     *
     * @param user 【当前登录用户】
     * @return 【更新后的宠物实体】
     */
    @Transactional
    public Pet feed(UserAccount user) {
        var pet = getActiveOrNull(user);
        if (pet == null) return null;
        pet.satiety = Math.min(100, (pet.satiety == null ? 80 : pet.satiety) + 20);
        pet.mood = Math.min(100, (pet.mood == null ? 80 : pet.mood) + 5);
        pet.updatedAt = LocalDateTime.now();
        return pets.save(pet);
    }

    /**
     * 【获取当前用户最近20条经验变动日志，按创建时间倒序排列。】
     *
     * @param user 【当前登录用户】
     * @return 【经验日志列表，最多20条】
     */
    public List<ExpLog> logs(UserAccount user) {
        return expLogs.findTop20ByUserOrderByCreatedAtDesc(user);
    }

    /**
     * 【检查指定任务是否有关联的经验日志记录，用于判断任务是否可以被安全删除。】
     *
     * @param task 【学习任务】
     * @return 【如果存在关联日志返回 true】
     */
    public boolean hasLogs(StudyTask task) {
        return expLogs.existsByTask(task);
    }

    /**
     * 【保存经验变动日志：记录每次经验值变动的详细信息，包括关联的任务、提交记录、
     *  经验量、事件类型和原因说明。】
     */
    private void saveLog(UserAccount user, StudyTask task, TaskSubmission submission, int amount, String eventType, String reason) {
        var log = new ExpLog();
        log.user = user;
        log.task = task;
        log.submission = submission;
        log.expAmount = amount;
        log.eventType = eventType;
        log.reason = reason;
        expLogs.save(log);
    }

    /**
     * 【安全字符串处理：null 转为空字符串，避免 NullPointerException。】
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    // ===================== 宠物市场 / 拥有关系 =====================

    /** 【宠物市场目录：全部上架品种（按展示顺序）】 */
    @Transactional(readOnly = true)
    public List<PetSpecies> listSpecies() {
        return species.findAllByOrderBySortOrderAsc();
    }

    /** 【当前用户拥有的全部宠物（按获得时间）】 */
    @Transactional(readOnly = true)
    public List<Pet> owned(UserAccount user) {
        return pets.findByUserOrderByAcquiredAtAsc(user);
    }

    /**
     * 【免费领养首只宠物：仅当用户尚无任何宠物、且所选品种是入门款时可领养，自动设为出战。】
     *
     * @throws BadRequestException 已有宠物 / 该品种非入门款
     * @throws NotFoundException   品种不存在
     */
    @Transactional
    public Pet adoptStarter(UserAccount user, String slug) {
        if (pets.existsByUser(user)) throw new BadRequestException("你已经有宠物啦，去市场购买更多吧");
        var sp = species.findBySlug(slug).orElseThrow(() -> new NotFoundException("宠物品种不存在"));
        if (!sp.starter) throw new BadRequestException("该品种不是入门款，不能免费领养");
        return createPet(user, sp, true);
    }

    /**
     * 【购买宠物：扣金币（价格 0 则免费），不可重复购买同款；若是第一只则自动出战。】
     *
     * @throws BadRequestException 已拥有该品种 / 金币不足
     * @throws NotFoundException   品种不存在
     */
    @Transactional
    public Pet buy(UserAccount user, String slug) {
        var sp = species.findBySlug(slug).orElseThrow(() -> new NotFoundException("宠物品种不存在"));
        if (pets.existsByUserAndSpecies(user, sp)) throw new BadRequestException("你已经拥有这只宠物啦");
        boolean first = !pets.existsByUser(user);
        if (sp.price > 0) {
            coins.spend(user, sp.price, "PURCHASE", "PET_SPECIES", sp.id, "购买宠物：" + sp.name);
        }
        return createPet(user, sp, first);
    }

    /** 【切换出战宠物：把目标设为 active，原出战取消】 */
    @Transactional
    public Pet setActive(UserAccount user, Long petId) {
        var target = pets.findByIdAndUser(petId, user).orElseThrow(() -> new NotFoundException("宠物不存在"));
        deactivateCurrent(user, target.id);
        target.active = true;
        target.updatedAt = LocalDateTime.now();
        return pets.save(target);
    }

    /** 【新建一只宠物实例；active=true 时先取消原出战宠物】 */
    private Pet createPet(UserAccount user, PetSpecies sp, boolean active) {
        if (active) deactivateCurrent(user, null);
        var pet = new Pet();
        pet.user = user;
        pet.species = sp;
        pet.name = sp.name;
        pet.avatarUrl = null;
        pet.active = active;
        pet.acquiredAt = LocalDateTime.now();
        pet.createdAt = LocalDateTime.now();
        pet.updatedAt = LocalDateTime.now();
        return pets.save(pet);
    }

    /** 【取消当前出战宠物（exceptId 为新出战者时跳过自身）】 */
    private void deactivateCurrent(UserAccount user, Long exceptId) {
        pets.findByUserAndActiveTrue(user).ifPresent(cur -> {
            if (exceptId == null || !cur.id.equals(exceptId)) {
                cur.active = false;
                pets.save(cur);
            }
        });
    }

    /**
     * 【宠物 JSON 视图：只暴露前端需要的字段，避免序列化整个 user 实体；附带品种信息。
     *  pet 为 null 时返回 null（前端按「未领养」处理）。】
     */
    public static java.util.Map<String, Object> view(Pet pet) {
        if (pet == null) return null;
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", pet.id);
        m.put("name", pet.name);
        m.put("avatarUrl", pet.avatarUrl == null ? "" : pet.avatarUrl);
        m.put("level", pet.level);
        m.put("currentExp", pet.currentExp);
        m.put("nextLevelExp", pet.nextLevelExp);
        m.put("mood", pet.mood);
        m.put("satiety", pet.satiety);
        m.put("growthStage", pet.growthStage);
        m.put("status", pet.status);
        m.put("active", pet.active);
        m.put("species", speciesView(pet.species));
        return m;
    }

    /** 【品种 JSON 视图】 */
    public static java.util.Map<String, Object> speciesView(PetSpecies sp) {
        if (sp == null) return null;
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", sp.id);
        m.put("slug", sp.slug);
        m.put("name", sp.name);
        m.put("rarity", sp.rarity);
        m.put("price", sp.price);
        m.put("starter", sp.starter);
        m.put("spritePath", sp.spritePath);
        m.put("description", sp.description);
        return m;
    }
}
