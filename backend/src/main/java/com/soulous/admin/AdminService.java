package com.soulous.admin;

import com.soulous.ai.AiReviewRepository;
import com.soulous.appeal.Appeal;
import com.soulous.appeal.AppealRepository;
import com.soulous.appeal.AppealStatus;
import com.soulous.auth.UserAccount;
import com.soulous.common.exception.NotFoundException;
import com.soulous.notification.NotificationService;
import com.soulous.notification.NotificationType;
import com.soulous.pet.PetRepository;
import com.soulous.pet.PetService;
import com.soulous.rag.RetrievalService;
import com.soulous.task.SubmissionRepository;
import com.soulous.task.SubmissionStatus;
import com.soulous.task.TaskRepository;
import com.soulous.task.TaskStatus;
import com.soulous.task.TaskSubmission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【管理员业务服务层，封装所有管理员操作的核心业务逻辑。
 * 包括提交审核（批准/驳回/需补充）、申诉审核、审核日志管理、
 * 提交和申诉列表查询等功能。
 * 审核操作涉及多表联动：更新提交状态、任务状态、发放经验值、
 * 索引完成任务、记录审核日志、发送通知等。】
 */
@Service
public class AdminService {
    private final SubmissionRepository submissions;
    private final AppealRepository appeals;
    private final AiReviewRepository reviews;
    private final TaskRepository tasks;
    private final PetService pets;
    private final PetRepository petRepo;
    private final AdminAuditLogRepository audit;
    private final RetrievalService retrieval;
    private final NotificationService notifications;
    /** 【金币服务，人工/申诉复核通过时发放金币奖励】 */
    private final com.soulous.wallet.CoinService coins;

    AdminService(SubmissionRepository submissions, AppealRepository appeals, AiReviewRepository reviews,
                 TaskRepository tasks, PetService pets, PetRepository petRepo,
                 AdminAuditLogRepository audit, RetrievalService retrieval,
                 NotificationService notifications, com.soulous.wallet.CoinService coins) {
        this.submissions = submissions;
        this.appeals = appeals;
        this.reviews = reviews;
        this.tasks = tasks;
        this.pets = pets;
        this.petRepo = petRepo;
        this.audit = audit;
        this.retrieval = retrieval;
        this.notifications = notifications;
        this.coins = coins;
    }

    /**
     * 【记录管理员审核操作日志的通用方法。
     * 构建 AdminAuditLog 实体并持久化，用于操作审计追踪。】
     *
     * @param admin       【执行操作的管理员账号】
     * @param action      【审核操作类型枚举】
     * @param targetType  【操作目标类型枚举】
     * @param targetId    【操作目标ID】
     * @param expAmount   【经验值变动数量，可为 null】
     * @param resultStatus【操作结果状态】
     * @param comment     【审核意见/评论】
     */
    private void logAudit(UserAccount admin, AdminAuditAction action, AuditTargetType targetType,
                          Long targetId, Integer expAmount, String resultStatus, String comment) {
        var entry = new AdminAuditLog();
        entry.adminId = admin == null ? null : admin.id;
        entry.adminUsername = admin == null ? null : admin.username;
        entry.action = action;
        entry.targetType = targetType;
        entry.targetId = targetId;
        entry.expAmount = expAmount;
        entry.resultStatus = resultStatus;
        entry.comment = comment;
        audit.save(entry);
    }

    /**
     * 【查询最近 100 条管理员审核日志，按创建时间倒序排列。】
     *
     * @return 【审核日志列表】
     */
    public List<AdminAuditLog> recentAudit() {
        return audit.findTop100ByOrderByCreatedAtDesc();
    }

    /**
     * 【查询指定提交的所有审核日志记录，按创建时间倒序排列。】
     *
     * @param submissionId 【提交记录ID】
     * @return 【该提交的审核日志列表】
     */
    public List<AdminAuditLog> auditForSubmission(Long submissionId) {
        return audit.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(AuditTargetType.SUBMISSION, submissionId);
    }

    /**
     * 【查询提交列表，支持"待审核"和"全部"两种筛选模式。
     * 待审核模式只返回 PENDING 和 AI_REJECTED 状态的提交。
     * 每条记录附加用户宠物等级信息。】
     *
     * @param scope 【筛选范围，"todo" 或 "all"】
     * @return 【提交列表，每项包含 submission 和 petLevel】
     */
    public List<Map<String, Object>> submissions(String scope) {
        var all = submissions.findAllByOrderByCreatedAtDesc();
        var filtered = "all".equalsIgnoreCase(scope) ? all : all.stream()
                .filter(s -> s.status == SubmissionStatus.PENDING
                        || s.status == SubmissionStatus.AI_REJECTED)
                .toList();
        var levelByUser = new HashMap<Long, Integer>();
        return filtered.stream().map(s -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("submission", s);
            Integer level = s.user == null ? null : levelByUser.computeIfAbsent(s.user.id,
                    uid -> petRepo.findByUserAndActiveTrue(s.user).map(p -> p.level).orElse(null));
            item.put("petLevel", level);
            return (Map<String, Object>) item;
        }).toList();
    }

    /**
     * 【查询单个提交的详细信息。
     * 返回提交记录、关联任务、用户信息、AI审核结果和宠物等级的完整详情。】
     *
     * @param id 【提交记录ID】
     * @return 【包含 submission、task、user、review、petLevel 的详情 Map】
     */
    public Map<String, Object> submission(Long id) {
        var submission = submissions.findById(id).orElseThrow(() -> new NotFoundException("Submission not found"));
        var body = new LinkedHashMap<String, Object>();
        body.put("submission", submission);
        body.put("task", submission.task);
        body.put("user", submission.user);
        body.put("review", reviews.findBySubmission(submission).orElse(null));
        body.put("petLevel", submission.user == null ? null
                : petRepo.findByUserAndActiveTrue(submission.user).map(p -> p.level).orElse(null));
        return body;
    }

    /**
     * 【批准提交的业务逻辑。
     * 1. 查找提交记录，若不存在则抛出异常；
     * 2. 确定经验值（使用请求中的 expAmount 或任务默认基础经验值）；
     * 3. 更新提交状态为 MANUAL_APPROVED，设置管理员评论；
     * 4. 标记任务为已完成，记录完成时间；
     * 5. 调用 PetService 为用户发放经验值；
     * 6. 将完成的任务索引到 RAG 检索服务；
     * 7. 记录审核日志。
     * 整个过程在同一事务中执行。】
     *
     * @param admin   【执行操作的管理员】
     * @param id      【提交记录ID】
     * @param request 【审核请求体，包含经验值和评论】
     * @return 【包含更新后 submission 和 task 的 Map】
     */
    @Transactional
    public Map<String, Object> approve(UserAccount admin, Long id, AdminReviewRequest request) {
        var submission = submissions.findById(id).orElseThrow(() -> new NotFoundException("Submission not found"));
        var exp = request.expAmount() == null ? submission.task.baseExp : request.expAmount();
        submission.status = SubmissionStatus.MANUAL_APPROVED;
        submission.adminComment = safe(request.comment());
        submission.task.status = TaskStatus.COMPLETED;
        submission.task.completedAt = LocalDateTime.now();
        pets.addExp(submission.user, submission.task, submission, exp, "人工复核通过：" + safe(request.comment()));
        coins.grant(submission.user, Math.max(5, (int) Math.round(exp * 0.5)), "TASK", "SUBMISSION", submission.id, "人工复核通过：" + safe(submission.task.title));
        var saved = submissions.save(submission);
        var savedTask = tasks.save(submission.task);
        retrieval.indexCompletedTask(savedTask);
        logAudit(admin, AdminAuditAction.APPROVE, AuditTargetType.SUBMISSION, id, exp,
                SubmissionStatus.MANUAL_APPROVED.name(), safe(request.comment()));
        return Map.of("submission", saved, "task", savedTask);
    }

    /**
     * 【驳回提交的业务逻辑。
     * 更新提交状态为 MANUAL_REJECTED，标记任务为手动驳回，
     * 调用 PetService 记录驳回状态，保存并记录审核日志。】
     *
     * @param admin   【执行操作的管理员】
     * @param id      【提交记录ID】
     * @param request 【审核请求体】
     * @return 【更新后的提交记录】
     */
    @Transactional
    public TaskSubmission reject(UserAccount admin, Long id, AdminReviewRequest request) {
        var submission = submissions.findById(id).orElseThrow(() -> new NotFoundException("Submission not found"));
        submission.status = SubmissionStatus.MANUAL_REJECTED;
        submission.adminComment = safe(request.comment());
        submission.task.status = TaskStatus.MANUAL_REJECTED;
        pets.markRejected(submission.user, submission.task, submission, safe(request.comment()));
        tasks.save(submission.task);
        var saved = submissions.save(submission);
        logAudit(admin, AdminAuditAction.REJECT, AuditTargetType.SUBMISSION, id, null,
                SubmissionStatus.MANUAL_REJECTED.name(), safe(request.comment()));
        return saved;
    }

    /**
     * 【要求补充材料的业务逻辑。
     * 更新提交状态为 NEED_MORE，标记任务为需补充状态，
     * 调用 PetService 记录需补充状态，保存并记录审核日志。】
     *
     * @param admin   【执行操作的管理员】
     * @param id      【提交记录ID】
     * @param request 【审核请求体】
     * @return 【更新后的提交记录】
     */
    @Transactional
    public TaskSubmission needMore(UserAccount admin, Long id, AdminReviewRequest request) {
        var submission = submissions.findById(id).orElseThrow(() -> new NotFoundException("Submission not found"));
        submission.status = SubmissionStatus.NEED_MORE;
        submission.adminComment = safe(request.comment());
        submission.task.status = TaskStatus.NEED_MORE;
        pets.markNeedsMore(submission.user, submission.task, submission, safe(request.comment()));
        tasks.save(submission.task);
        var saved = submissions.save(submission);
        logAudit(admin, AdminAuditAction.NEED_MORE, AuditTargetType.SUBMISSION, id, null,
                SubmissionStatus.NEED_MORE.name(), safe(request.comment()));
        return saved;
    }

    /**
     * 【查询申诉列表，支持"待审核"和"全部"两种筛选模式。
     * 待审核模式只返回 PENDING 状态的申诉。
     * 每条记录附加提交、任务、用户、宠物等级和AI审核信息。】
     *
     * @param scope 【筛选范围，"todo" 或 "all"】
     * @return 【申诉列表，每项包含 appeal、submission、task、user、petLevel、aiReview】
     */
    public List<Map<String, Object>> appeals(String scope) {
        var all = appeals.findAllByOrderByCreatedAtDesc();
        var filtered = "all".equalsIgnoreCase(scope) ? all : all.stream()
                .filter(a -> a.status == AppealStatus.PENDING)
                .toList();
        var levelByUser = new HashMap<Long, Integer>();
        return filtered.stream().map(a -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("appeal", a);
            item.put("submission", a.submission);
            item.put("task", a.submission == null ? null : a.submission.task);
            item.put("user", a.user);
            Integer level = a.user == null ? null : levelByUser.computeIfAbsent(a.user.id,
                    uid -> petRepo.findByUserAndActiveTrue(a.user).map(p -> p.level).orElse(null));
            item.put("petLevel", level);
            item.put("aiReview", a.submission == null ? null
                    : reviews.findBySubmission(a.submission).orElse(null));
            return (Map<String, Object>) item;
        }).toList();
    }

    /**
     * 【审核申诉的业务逻辑。
     * 根据审核状态执行不同操作：
     * - APPROVED（通过）：标记提交和任务为完成，发放经验值，索引到 RAG；
     * - REJECTED（驳回）：标记提交和任务为手动驳回；
     * - NEED_MORE（需补充）：标记提交和任务为需补充状态。
     * 最后更新申诉记录、记录审核日志、发送通知给用户。】
     *
     * @param admin     【执行操作的管理员】
     * @param id        【申诉记录ID】
     * @param status    【审核结果状态】
     * @param expAmount 【可选的经验值数量，仅在通过时使用】
     * @param comment   【审核意见/评论】
     * @return 【更新后的申诉记录】
     */
    @Transactional
    public Appeal reviewAppeal(UserAccount admin, Long id, AppealStatus status, Integer expAmount, String comment) {
        var appeal = appeals.findById(id).orElseThrow(() -> new NotFoundException("Appeal not found"));
        appeal.status = status;
        appeal.adminId = admin.id;
        appeal.adminComment = comment;
        appeal.reviewedAt = LocalDateTime.now();
        var submission = appeal.submission;
        var safeComment = safe(comment);
        Integer awardedExp = null;
        if (submission != null) {
            var task = submission.task;
            if (status == AppealStatus.APPROVED) {
                awardedExp = expAmount == null ? task.baseExp : Math.max(0, expAmount);
                submission.status = SubmissionStatus.MANUAL_APPROVED;
                submission.adminComment = safeComment;
                task.status = TaskStatus.COMPLETED;
                task.completedAt = LocalDateTime.now();
                pets.addExp(submission.user, task, submission, awardedExp, "申诉通过：" + safeComment);
                coins.grant(submission.user, Math.max(5, (int) Math.round(awardedExp * 0.5)), "TASK", "SUBMISSION", submission.id, "申诉通过：" + safe(task.title));
                retrieval.indexCompletedTask(task);
            } else if (status == AppealStatus.REJECTED) {
                submission.status = SubmissionStatus.MANUAL_REJECTED;
                submission.adminComment = safeComment;
                task.status = TaskStatus.MANUAL_REJECTED;
                pets.markRejected(submission.user, task, submission, safeComment);
            } else if (status == AppealStatus.NEED_MORE) {
                submission.status = SubmissionStatus.NEED_MORE;
                submission.adminComment = safeComment;
                task.status = TaskStatus.NEED_MORE;
                pets.markNeedsMore(submission.user, task, submission, safeComment);
            }
            submissions.save(submission);
            tasks.save(task);
        }
        var saved = appeals.save(appeal);
        logAudit(admin, AdminAuditAction.APPEAL_REVIEW, AuditTargetType.APPEAL, id, awardedExp,
                status.name(), safeComment);
        if (submission != null) {
            var title = switch (status) {
                case APPROVED -> "申诉通过：" + submission.task.title;
                case REJECTED -> "申诉被驳回：" + submission.task.title;
                case NEED_MORE -> "申诉需补充：" + submission.task.title;
                case PENDING -> "申诉状态更新：" + submission.task.title;
            };
            notifications.push(submission.user, NotificationType.APPEAL_REVIEWED,
                    title, safeComment, "APPEAL", saved.id);
        }
        return saved;
    }

    /**
     * 【安全的空值处理方法，将 null 转换为空字符串。】
     *
     * @param value 【原始字符串】
     * @return 【若为 null 则返回空字符串，否则返回原值】
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }
}
