package com.soulous.pet;

import com.soulous.auth.UserAccount;
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

    PetService(PetRepository pets, ExpLogRepository expLogs) {
        this.pets = pets;
        this.expLogs = expLogs;
    }

    /**
     * 【获取当前用户的宠物，如果不存在则抛出 NotFoundException。】
     *
     * @param user 【当前登录用户】
     * @return 【宠物实体】
     * @throws NotFoundException 【宠物不存在时抛出】
     */
    public Pet get(UserAccount user) {
        return pets.findByUser(user).orElseThrow(() -> new NotFoundException("Pet not found"));
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
        var pet = get(user);
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
        var pet = get(user);
        var result = PetGrowthRules.applyFocusReward(pet, amount);
        pet.updatedAt = LocalDateTime.now();
        pets.save(pet);
        saveLog(user, null, null, result.expAmount(), "FOCUS_EXP", reason);
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
        var pet = get(user);
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
        var pet = get(user);
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
        var pet = get(user);
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
        var pet = get(user);
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
        var pet = get(user);
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
        var pet = get(user);
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
        var pet = get(user);
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
}
