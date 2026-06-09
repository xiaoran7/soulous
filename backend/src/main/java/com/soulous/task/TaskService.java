package com.soulous.task;

import com.soulous.ai.AiReview;
import com.soulous.ai.AiReviewRepository;
import com.soulous.ai.AiReviewResult;
import com.soulous.ai.AiService;
import com.soulous.appeal.AppealRepository;
import com.soulous.auth.UserAccount;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.moderation.ModerationService;
import com.soulous.notification.NotificationService;
import com.soulous.notification.NotificationType;
import com.soulous.pet.ExpLogRepository;
import com.soulous.pet.PetService;
import com.soulous.rag.RetrievalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 【学习任务核心业务服务：管理任务的完整生命周期，包括创建、编辑、状态流转、
 * 提交审核、AI 审核结果处理、经验值发放和通知推送】
 *
 * 【设计思路：作为任务模块的业务中枢，协调多个子系统——AI 审核（AiService）、
 * 宠物/经验值（PetService）、内容安全（ModerationService）、
 * RAG 索引（RetrievalService）、通知（NotificationService）。
 * 所有写操作使用 @Transactional 保证数据一致性。】
 */
@Service
public class TaskService {
    /** 【任务仓库】 */
    private final TaskRepository tasks;
    /** 【提交记录仓库】 */
    private final SubmissionRepository submissions;
    /** 【AI 审核结果仓库】 */
    private final AiReviewRepository reviews;
    /** 【学习记录仓库】 */
    private final StudyRecordRepository records;
    /** 【申诉记录仓库，用于删除任务时级联清理】 */
    private final AppealRepository appeals;
    /** 【经验值日志仓库，用于删除任务时级联清理】 */
    private final ExpLogRepository expLogs;
    /** 【AI 服务，负责 LLM 审核和追问评估】 */
    private final AiService ai;
    /** 【宠物服务，负责经验值发放和状态标记】 */
    private final PetService pets;
    /** 【RAG 检索服务，用于将已完成任务索引到向量库】 */
    private final RetrievalService retrieval;
    /** 【内容安全审核服务，提交时进行敏感词检测】 */
    private final ModerationService moderation;
    /** 【通知服务，用于推送 SSE 通知到前端】 */
    private final NotificationService notifications;
    /** 【金币服务，任务完成时发放金币奖励】 */
    private final com.soulous.wallet.CoinService coins;
    /**
     * 【延迟获取 ApplicationContext，用于打破循环依赖：
     * AiReviewProcessor 依赖 TaskService.applyAiReview，而 TaskService 需要调度 AiReviewProcessor。
     * 通过 appCtx.getBean() 延迟获取避免构造器循环。】
     * Lazy to break the cycle: AiReviewProcessor depends on TaskService.applyAiReview.
     */
    private final org.springframework.context.ApplicationContext appCtx;
    /**
     * 【AI 审核异步执行线程池，生产环境使用专用线程池，测试环境使用 SyncTaskExecutor】
     * Pool that runs the async AI review post-commit. SyncTaskExecutor in tests.
     */
    private final org.springframework.core.task.TaskExecutor aiReviewExecutor;

    /**
     * 【构造注入所有依赖项】
     *
     * @param tasks             【任务仓库】
     * @param submissions       【提交记录仓库】
     * @param reviews           【AI 审核结果仓库】
     * @param records           【学习记录仓库】
     * @param appeals           【申诉记录仓库】
     * @param expLogs           【经验值日志仓库】
     * @param ai                【AI 服务】
     * @param pets              【宠物服务】
     * @param retrieval         【RAG 检索服务】
     * @param moderation        【内容安全审核服务】
     * @param notifications     【通知服务】
     * @param appCtx            【Spring 应用上下文，用于延迟获取 Bean】
     * @param aiReviewExecutor  【AI 审核线程池，通过 @Qualifier 注入】
     */
    TaskService(TaskRepository tasks, SubmissionRepository submissions, AiReviewRepository reviews,
                StudyRecordRepository records, AppealRepository appeals, ExpLogRepository expLogs,
                AiService ai, PetService pets, RetrievalService retrieval, ModerationService moderation,
                NotificationService notifications,
                com.soulous.wallet.CoinService coins,
                org.springframework.context.ApplicationContext appCtx,
                @org.springframework.beans.factory.annotation.Qualifier("aiReviewExecutor")
                org.springframework.core.task.TaskExecutor aiReviewExecutor) {
        this.tasks = tasks;
        this.submissions = submissions;
        this.reviews = reviews;
        this.records = records;
        this.appeals = appeals;
        this.expLogs = expLogs;
        this.ai = ai;
        this.pets = pets;
        this.retrieval = retrieval;
        this.moderation = moderation;
        this.notifications = notifications;
        this.coins = coins;
        this.appCtx = appCtx;
        this.aiReviewExecutor = aiReviewExecutor;
    }

    /**
     * 【获取当前用户的所有任务列表，按创建时间倒序】
     *
     * @param user 【当前登录用户】
     * @return 【任务列表】
     */
    public List<StudyTask> list(UserAccount user) {
        return tasks.findByUserOrderByCreatedAtDesc(user);
    }

    /**
     * 【获取当前用户的所有提交记录，按创建时间倒序】
     *
     * @param user 【当前登录用户】
     * @return 【提交记录列表】
     */
    public List<TaskSubmission> submissions(UserAccount user) {
        return submissions.findByUserOrderByCreatedAtDesc(user);
    }

    /**
     * 【获取提交记录详情，包含关联的任务和审核结果】
     *
     * 【权限校验：验证提交记录属于当前用户，否则抛出 ForbiddenException】
     *
     * @param user 【当前登录用户】
     * @param id   【提交记录 ID】
     * @return 【包含 submission、task、review 的 LinkedHashMap（保持字段顺序）】
     * @throws NotFoundException   【提交记录不存在】
     * @throws ForbiddenException  【提交记录不属于当前用户】
     */
    public Map<String, Object> submissionDetail(UserAccount user, Long id) {
        var submission = submissions.findById(id).orElseThrow(() -> new NotFoundException("Submission not found"));
        if (!Objects.equals(submission.user.id, user.id)) {
            throw new ForbiddenException("Submission belongs to another user");
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("submission", submission);
        body.put("task", submission.task);
        body.put("review", reviews.findBySubmission(submission).orElse(null));
        return body;
    }

    /**
     * 【获取单个任务详情，带权限校验】
     *
     * @param user 【当前登录用户】
     * @param id   【任务 ID】
     * @return 【任务实体】
     * @throws NotFoundException   【任务不存在】
     * @throws ForbiddenException  【任务不属于当前用户】
     */
    public StudyTask one(UserAccount user, Long id) {
        var task = tasks.findById(id).orElseThrow(() -> new NotFoundException("Task not found"));
        if (!Objects.equals(task.user.id, user.id)) throw new ForbiddenException("Task belongs to another user");
        return task;
    }

    /**
     * 【创建新学习任务】
     *
     * @param user    【当前登录用户】
     * @param request 【任务请求 DTO】
     * @return 【保存后的任务实体（含生成的 ID）】
     */
    @Transactional
    public StudyTask create(UserAccount user, TaskRequest request) {
        var task = new StudyTask();
        task.user = user;
        apply(task, request);
        return tasks.save(task);
    }

    /** 【允许编辑的任务状态集合：只有待办、需要补充、AI 拒绝状态的任务可编辑】 */
    private static final java.util.Set<TaskStatus> EDITABLE_STATUSES =
            java.util.Set.of(TaskStatus.TODO, TaskStatus.NEED_MORE, TaskStatus.AI_REJECTED);

    /**
     * 【更新已有任务，仅允许编辑 TODO/NEED_MORE/AI_REJECTED 状态的任务】
     *
     * @param user    【当前登录用户】
     * @param id      【任务 ID】
     * @param request 【更新内容】
     * @return 【更新后的任务实体】
     * @throws IllegalStateException 【任务状态不允许编辑】
     */
    @Transactional
    public StudyTask update(UserAccount user, Long id, TaskRequest request) {
        var task = one(user, id);
        if (!EDITABLE_STATUSES.contains(task.status)) {
            throw new IllegalStateException("任务已开始，无法编辑。请先暂停或完成当前任务。");
        }
        apply(task, request);
        return tasks.save(task);
    }

    /**
     * 【暂停进行中的任务】
     *
     * @param user 【当前登录用户】
     * @param id   【任务 ID】
     * @return 【更新后的任务实体】
     * @throws IllegalStateException 【仅 DOING 状态可暂停】
     */
    @Transactional
    public StudyTask pause(UserAccount user, Long id) {
        var task = one(user, id);
        if (task.status != TaskStatus.DOING) {
            throw new IllegalStateException("只有进行中的任务才能暂停。");
        }
        task.status = TaskStatus.PAUSED;
        return tasks.save(task);
    }

    /**
     * 【恢复暂停的任务，回到 DOING 状态。如果任务从未开始过，同时记录开始时间】
     *
     * @param user 【当前登录用户】
     * @param id   【任务 ID】
     * @return 【更新后的任务实体】
     * @throws IllegalStateException 【仅 PAUSED 状态可恢复】
     */
    @Transactional
    public StudyTask resume(UserAccount user, Long id) {
        var task = one(user, id);
        if (task.status != TaskStatus.PAUSED) {
            throw new IllegalStateException("只有暂停的任务才能继续。");
        }
        task.status = TaskStatus.DOING;
        // 【首次恢复时设置开始时间（防御性编程，正常流程 start() 已设置）】
        if (task.startedAt == null) task.startedAt = LocalDateTime.now();
        return tasks.save(task);
    }

    /**
     * 【删除任务及其所有关联数据（级联删除）】
     *
     * 【删除顺序：申诉记录 → AI 审核记录 → 提交记录 → 经验值日志 → 学习记录 → 任务本身】
     *
     * @param user 【当前登录用户】
     * @param id   【任务 ID】
     */
    @Transactional
    public void delete(UserAccount user, Long id) {
        var task = one(user, id);
        var related = submissions.findByTask(task);
        if (!related.isEmpty()) {
            appeals.deleteBySubmissionIn(related);
            reviews.deleteBySubmissionIn(related);
        }
        submissions.deleteByTask(task);
        expLogs.deleteByTask(task);
        records.deleteByTask(task);
        tasks.delete(task);
    }

    /**
     * 【开始任务，将状态从 TODO 变为 DOING，记录开始时间，通知宠物系统】
     *
     * @param user 【当前登录用户】
     * @param id   【任务 ID】
     * @return 【更新后的任务实体】
     */
    @Transactional
    public StudyTask start(UserAccount user, Long id) {
        var task = one(user, id);
        task.status = TaskStatus.DOING;
        task.startedAt = LocalDateTime.now();
        var saved = tasks.save(task);
        pets.markTaskStarted(user, saved);
        return saved;
    }

    /**
     * 【提交任务学习证明，触发内容安全审核和异步 AI 审核流程】
     *
     * 【完整流程：
     * 1. 校验任务状态（仅 DOING/NEED_MORE/AI_REJECTED/MODERATION_BLOCKED 可提交）
     * 2. 构建提交记录，合并多张截图 URL
     * 3. 执行内容安全审核（ModerationService），被拦截则直接返回
     * 4. 持久化提交记录（状态 AI_REVIEWING）和更新任务状态（SUBMITTED）
     * 5. 创建学习记录，通知宠物系统
     * 6. 注册 TransactionSynchronization.afterCompletion 钩子，
     *    在事务完全提交后异步调度 AiReviewProcessor 执行 LLM 审核
     * 7. 返回即时响应（review 为 null，前端显示"审核中..."）】
     *
     * @param user    【当前登录用户】
     * @param id      【任务 ID】
     * @param request 【提交请求体】
     * @return 【包含 task、submission、review、aiQuestion 的响应 Map】
     * @throws IllegalStateException 【任务状态不允许提交】
     */
    @Transactional
    public Map<String, Object> submit(UserAccount user, Long id, SubmitTaskRequest request) {
        var task = one(user, id);
        var allowed = java.util.Set.of(TaskStatus.DOING, TaskStatus.NEED_MORE, TaskStatus.AI_REJECTED, TaskStatus.MODERATION_BLOCKED);
        if (!allowed.contains(task.status)) {
            if (task.status == TaskStatus.TODO) {
                throw new IllegalStateException("任务还未开始，请先点击「开始」再提交。");
            }
            if (task.status == TaskStatus.PAUSED) {
                throw new IllegalStateException("任务处于暂停状态，请先继续任务再提交。");
            }
            throw new IllegalStateException("当前任务状态（" + task.status + "）不允许提交，请检查任务进度。");
        }
        // 【构建提交记录实体】
        var submission = new TaskSubmission();
        submission.user = user;
        submission.task = task;
        submission.textProof = request.textProof();
        submission.codeSnippet = request.codeSnippet();
        submission.proofLink = request.proofLink();
        submission.studyMinutes = request.studyMinutes() == null ? 0 : request.studyMinutes();
        // 【合并截图 URL：优先使用 screenshotUrls 列表，回退到 screenshotUrl 单值】
        var urls = new ArrayList<String>();
        if (request.screenshotUrls() != null) {
            for (var u : request.screenshotUrls()) if (u != null && !u.isBlank()) urls.add(u.trim());
        }
        if (urls.isEmpty() && request.screenshotUrl() != null && !request.screenshotUrl().isBlank()) {
            urls.add(request.screenshotUrl().trim());
        }
        submission.screenshotUrls = urls.isEmpty() ? null : urls.stream().collect(Collectors.joining(","));
        submission.screenshotUrl = urls.isEmpty() ? null : urls.get(0);
        submission.submitType = detectSubmitType(submission);

        // --- Moderation gate ---
        // Combined free-text from all proof channels; we don't moderate URLs or screenshots here.
        // When moderation is disabled (default) this is a no-op PASS.
        // 【内容安全审核关卡：合并所有文本类证明渠道进行敏感词检测，
        // 不审核 URL 和截图内容。当内容安全功能禁用时（默认），此步骤为空操作 PASS。】
        var combinedText = java.util.stream.Stream.of(submission.textProof, submission.codeSnippet, submission.proofLink)
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        if (!combinedText.isBlank()) {
            var verdict = moderation.moderateInput(user, combinedText, java.util.List.of(), null);
            if (verdict.blocked()) {
                // 【内容被拦截：设置提交和任务为 MODERATION_BLOCKED 状态，直接返回】
                submission.status = SubmissionStatus.MODERATION_BLOCKED;
                submission.moderationReason = verdict.reason();
                submission.updatedAt = LocalDateTime.now();
                submissions.save(submission);
                task.status = TaskStatus.MODERATION_BLOCKED;
                task.submittedAt = LocalDateTime.now();
                tasks.save(task);
                var response = new LinkedHashMap<String, Object>();
                response.put("task", task);
                response.put("submission", submission);
                response.put("review", null);
                response.put("aiQuestion", "");
                response.put("moderationBlocked", true);
                response.put("moderationReason", verdict.reason());
                return response;
            }
        }

        // Persist the submission as AI_REVIEWING so the HTTP response can return immediately.
        // The actual LLM call now runs on the aiReviewExecutor pool — see AiReviewProcessor.
        // UI shows "审核中..." until an SSE notification (AI_REVIEW_DONE) fires and the page
        // re-fetches the submission/task to display the verdict.
        // 【将提交记录持久化为 AI_REVIEWING 状态，使 HTTP 响应可以立即返回。
        // 实际的 LLM 调用在 aiReviewExecutor 线程池中运行（见 AiReviewProcessor）。
        // 前端显示"审核中..."，直到 SSE 通知（AI_REVIEW_DONE）触发页面重新获取结果。】
        submission.status = SubmissionStatus.AI_REVIEWING;
        submissions.save(submission);

        task.status = TaskStatus.SUBMITTED;
        task.submittedAt = LocalDateTime.now();
        task.actualMinutes = submission.studyMinutes;
        tasks.save(task);
        pets.markSubmittedForReview(user, task, submission);

        // 【创建学习记录，记录本次学习时长和摘要】
        var record = new StudyRecord();
        record.user = user;
        record.task = task;
        record.studyMinutes = submission.studyMinutes;
        record.summary = submission.textProof;
        records.save(record);

        // Hand off to the async processor AFTER the surrounding @Transactional fully completes.
        // Using afterCompletion (status=COMMITTED) instead of afterCommit because afterCommit
        // fires while the TransactionSynchronizationManager still considers the original
        // transaction "active" — Spring's PROPAGATION_REQUIRED on the processor's @Transactional
        // would join that already-committed context instead of opening a fresh one, and any
        // entity updates in the new logical work would silently fail to persist. afterCompletion
        // fires AFTER the TSM is cleared, so the processor's tx starts truly fresh.
        // We lazily fetch AiReviewProcessor via ApplicationContext to side-step a constructor
        // cycle (AiReviewProcessor → TaskService.applyAiReview → TaskService → ...).
        //
        // 【事务钩子：在 @Transactional 完全提交后，将 AI 审核交给异步处理器。
        // 使用 afterCompletion（status=COMMITTED）而非 afterCommit，因为 afterCommit
        // 触发时 TransactionSynchronizationManager 仍认为原始事务"活跃"——
        // 处理器的 @Transactional(PROPAGATION_REQUIRED) 会加入已提交的上下文而非新开事务，
        // 导致新逻辑工作中的实体更新静默失败。afterCompletion 在 TSM 清除后触发，
        // 处理器的事务才能真正从零开始。
        // 通过 ApplicationContext 延迟获取 AiReviewProcessor 以避免构造器循环
        // （AiReviewProcessor → TaskService.applyAiReview → TaskService → ...）。】
        final var submissionId = submission.id;
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED) return;
                        aiReviewExecutor.execute(() ->
                                appCtx.getBean(AiReviewProcessor.class).runReview(submissionId));
                    }
                });

        // LinkedHashMap (not Map.of) because the values are intentionally null while the
        // async review is in flight; Map.of throws NPE on null values.
        // 【使用 LinkedHashMap 而非 Map.of，因为异步审核期间 review 值有意设为 null，
        // Map.of 不允许 null 值会抛出 NPE。】
        var response = new LinkedHashMap<String, Object>();
        response.put("task", task);
        response.put("submission", submission);
        response.put("review", null);
        response.put("aiQuestion", "");
        return response;
    }

    /**
     * 【回答 AI 追问问题，根据回答质量给予额外经验奖励】
     *
     * 【业务逻辑：AI 审核通过后可能会提出追问，用户回答后由 AI 评估质量，
     * 按分数梯度发放经验值（≥8 分优秀、≥4 分良好、>0 分一般、0 分无奖励），并反馈给宠物系统。】
     *
     * @param user         【当前登录用户】
     * @param submissionId 【提交记录 ID】
     * @param answer       【用户的回答内容】
     * @return 【包含 bonusExp（奖励经验值）、feedback（反馈文字）、pet（宠物状态）的 Map】
     * @throws NotFoundException   【提交记录不存在】
     * @throws ForbiddenException  【提交记录不属于当前用户】
     */
    @Transactional
    public Map<String, Object> answerAiQuestion(UserAccount user, Long submissionId, String answer) {
        var submission = submissions.findById(submissionId)
                .orElseThrow(() -> new NotFoundException("Submission not found"));
        if (!Objects.equals(submission.user.id, user.id)) throw new ForbiddenException("Not your submission");
        int bonus = ai.evaluateAnswer(submission.task, answer);
        String feedback;
        // 【按回答质量分梯度给予反馈】
        if (bonus >= 8) feedback = "回答很深入！获得 +" + bonus + " 经验加成。";
        else if (bonus >= 4) feedback = "回答不错！获得 +" + bonus + " 经验奖励。";
        else if (bonus > 0) feedback = "回答有一定参考价值，获得 +" + bonus + " 经验。";
        else feedback = "回答较简短，本次无加成，下次尝试展开说明。";
        var pet = bonus > 0 ? pets.addExp(user, submission.task, submission, bonus, "AI 追问奖励") : pets.getActiveOrNull(user);
        // pet 可能为 null（用户尚未领养宠物）；视图为 null 时用空 Map 占位，避免 Map.of 的 NPE
        var petView = com.soulous.pet.PetService.view(pet);
        return Map.of("bonusExp", bonus, "feedback", feedback, "pet", petView == null ? Map.of() : petView);
    }

    /**
     * 【应用 AI 审核结果，更新任务和提交记录的最终状态，并发送通知】
     *
     * 【处理逻辑：
     * - PASS：任务完成，发放经验值，索引到 RAG，通知"AI 审核通过"
     * - NEED_MORE：标记需要补充，通知"需要补充材料"
     * - REJECT：任务拒绝，通知"AI 未通过"】
     *
     * 【被 AiReviewProcessor.runReview() 在独立事务中调用】
     *
     * @param task       【学习任务】
     * @param submission 【提交记录】
     * @param review     【AI 审核结果】
     */
    @Transactional
    public void applyAiReview(StudyTask task, TaskSubmission submission, AiReview review) {
        String title;
        if (review.result == AiReviewResult.PASS) {
            // 【审核通过：标记任务完成，发放经验，索引到 RAG 知识库】
            task.status = TaskStatus.COMPLETED;
            task.completedAt = LocalDateTime.now();
            submission.status = SubmissionStatus.AI_APPROVED;
            pets.addExp(submission.user, task, submission, review.recommendedExp, "AI 审核通过：" + review.reason);
            // 完成任务发放金币：约为经验的一半，至少 5 枚
            int taskCoins = Math.max(5, (int) Math.round(review.recommendedExp * 0.5));
            coins.grant(submission.user, taskCoins, "TASK", "SUBMISSION", submission.id, "完成任务：" + task.title);
            retrieval.indexCompletedTask(task);
            title = "AI 审核通过：" + task.title;
        } else if (review.result == AiReviewResult.NEED_MORE) {
            // 【需要补充：标记任务和提交为 NEED_MORE，通知宠物系统】
            task.status = TaskStatus.NEED_MORE;
            submission.status = SubmissionStatus.NEED_MORE;
            pets.markNeedsMore(submission.user, task, submission, review.reason);
            title = "需要补充材料：" + task.title;
        } else {
            // 【审核拒绝：标记为 AI_REJECTED，通知宠物系统】
            task.status = TaskStatus.AI_REJECTED;
            submission.status = SubmissionStatus.AI_REJECTED;
            pets.markRejected(submission.user, task, submission, review.reason);
            title = "AI 未通过：" + task.title;
        }
        submission.updatedAt = LocalDateTime.now();
        tasks.save(task);
        submissions.save(submission);

        // 【发送 SSE 通知，前端收到后重新获取提交/任务详情显示审核结果】
        notifications.push(submission.user, NotificationType.AI_REVIEW_DONE,
                title, review.reason, "SUBMISSION", submission.id);
    }

    /**
     * 【将 TaskRequest DTO 的字段映射到 StudyTask 实体（私有辅助方法）】
     *
     * 【设计说明：非空字段才更新，避免将已有值覆盖为 null。
     * 任务类型和难度使用 null 检查保护，其余字段直接赋值。】
     *
     * @param task    【目标任务实体】
     * @param request 【请求 DTO】
     */
    private void apply(StudyTask task, TaskRequest request) {
        task.title = request.title();
        task.description = request.description();
        if (request.taskType() != null) task.taskType = request.taskType();
        if (request.difficulty() != null) task.difficulty = request.difficulty();
        task.courseName = request.courseName();
        task.category = normalizeCategory(request.category());
        if (request.estimatedMinutes() != null) task.estimatedMinutes = request.estimatedMinutes();
        if (request.baseExp() != null) task.baseExp = request.baseExp();
        task.deadline = request.deadline();
        if (request.scheduledWeekday() != null
                && request.scheduledWeekday() >= 1 && request.scheduledWeekday() <= 7) {
            task.scheduledWeekday = request.scheduledWeekday();
        }
    }

    /**
     * 【规整大分类：去首尾空白，空串归一为 null，超长截断到 64（与列宽一致）。】
     *
     * @param raw 【原始分类名，可空】
     * @return 【规整后的分类名，空白时返回 null】
     */
    private String normalizeCategory(String raw) {
        if (raw == null) return null;
        var trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    /**
     * 【检测提交类型，根据提交内容自动生成类型标识字符串】
     *
     * 【类型检测规则：
     * - TEXT：有文字证明
     * - TIMER：有学习时长记录
     * - SCREENSHOT：有截图
     * - CODE：有代码片段
     * - LINK：有证明链接
     * 多种类型以逗号连接，如 "TEXT,TIMER,SCREENSHOT"】
     *
     * @param submission 【提交记录】
     * @return 【提交类型标识字符串】
     */
    private String detectSubmitType(TaskSubmission submission) {
        var types = new ArrayList<String>();
        if (submission.textProof != null && !submission.textProof.isBlank()) types.add("TEXT");
        if (submission.studyMinutes != null && submission.studyMinutes > 0) types.add("TIMER");
        if ((submission.screenshotUrls != null && !submission.screenshotUrls.isBlank())
                || (submission.screenshotUrl != null && !submission.screenshotUrl.isBlank())) types.add("SCREENSHOT");
        if (submission.codeSnippet != null && !submission.codeSnippet.isBlank()) types.add("CODE");
        if (submission.proofLink != null && !submission.proofLink.isBlank()) types.add("LINK");
        return String.join(",", types);
    }
}
