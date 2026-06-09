package com.soulous.task;

import com.soulous.ai.AiReview;
import com.soulous.ai.AiReviewRepository;
import com.soulous.ai.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 【AI 审核处理器：在请求线程之外对新提交的 {@link TaskSubmission} 执行 AI 审核流程】
 *
 * 【设计思路：同步的 {@code TaskService.submit} 只负责将提交记录持久化为
 * {@link SubmissionStatus#AI_REVIEWING} 状态，然后调度本处理器异步执行。
 * 将 HTTP 请求往返与耗时 30-90 秒的 LLM 调用解耦，使用户获得即时响应，
 * 并通过 SSE 通知系统在审核完成后推送结果。】
 *
 * 【为什么独立为 Spring Bean：如果作为 TaskService 的私有方法，Spring 的
 * {@code @Async} 代理在同类内部调用时会被绕过，导致异步调用静默变为同步执行。
 * 独立为 Bean 可确保代理拦截生效。】
 *
 * <p>Runs an AI review on a freshly-submitted {@link TaskSubmission} off the request thread.</p>
 *
 * <p>The synchronous {@code TaskService.submit} now persists the submission with status
 * {@link SubmissionStatus#AI_REVIEWING}, then schedules this processor. Decoupling the HTTP
 * round-trip from a 30-90s LLM call gives the user an instant response and lets the SSE
 * notification system surface the verdict when it's actually ready.</p>
 *
 * <p>Lives in its own bean (not as a private method on TaskService) because Spring's
 * {@code @Async} proxy is bypassed when calling a method from the same class — same-class
 * invocations would silently run synchronously.</p>
 */
@Component
public class AiReviewProcessor {
    private static final Logger log = LoggerFactory.getLogger(AiReviewProcessor.class);

    /** 【提交记录仓库，用于查询和更新提交状态】 */
    private final SubmissionRepository submissions;
    /** 【AI 审核结果仓库，用于持久化审核记录】 */
    private final AiReviewRepository reviews;
    /** 【AI 服务，负责调用 LLM 进行内容审核】 */
    private final AiService ai;
    /** 【任务服务，用于在审核完成后更新任务和提交的最终状态】 */
    private final TaskService taskService;

    /**
     * 【构造注入所有依赖项】
     *
     * @param submissions 【提交记录仓库】
     * @param reviews     【AI 审核结果仓库】
     * @param ai          【AI 服务】
     * @param taskService 【任务服务】
     */
    AiReviewProcessor(SubmissionRepository submissions, AiReviewRepository reviews,
                      AiService ai, TaskService taskService) {
        this.submissions = submissions;
        this.reviews = reviews;
        this.ai = ai;
        this.taskService = taskService;
    }

    /**
     * 【触发 AI 审核流水线，对已持久化的提交记录执行审核】
     *
     * 【调用方说明：由 TaskService.submit 的 afterCompletion 钩子通过
     * {@code aiReviewExecutor} 线程池调度执行。曾经使用 {@code @Async} 注解，
     * 但该代理与条件 Bean 执行器交互不佳，在装配不完美时会静默同步执行，
     * 因此改为显式调度，使契约更加明确。】
     *
     * 【事务隔离说明：使用 REQUIRES_NEW 传播级别，因为发起 HTTP 请求的事务
     * 在本方法运行时已提交。但在 SyncTaskExecutor（测试环境）中，工作在同一线程执行，
     * Spring 的 ThreadLocal 绑定的 TransactionSynchronizationManager 可能仍将已提交的
     * 事务视为"活跃"并静默加入（导致所有写入因事务已结束而丢失）。
     * REQUIRES_NEW 在生产和测试环境中都强制开启全新的事务。】
     *
     * 【终态保证：所有退出路径（成功 / LLM 不可用 / 硬故障）都会将提交记录
     * 设为终态，确保 UI 不会永远停留在 AI_REVIEWING 状态。】
     *
     * <p>Fires the AI review pipeline for a previously-persisted submission.</p>
     *
     * <p>Caller (TaskService.submit's afterCommit hook) is responsible for dispatching this
     * via the {@code aiReviewExecutor} bean — we used to use {@code @Async} here but that
     * proxy interacts poorly with conditional-bean executors and silently runs synchronously
     * when the wiring isn't perfect. Explicit dispatch makes the contract obvious.</p>
     *
     * <p>Wrapped in REQUIRES_NEW because the originating HTTP transaction has already
     * committed by the time this method runs — but with SyncTaskExecutor (tests) the work
     * runs on the same thread, and Spring's ThreadLocal-bound TransactionSynchronizationManager
     * may still see the committed tx as "active" and silently join it (where all writes
     * disappear because the tx is already done). REQUIRES_NEW forces a real fresh tx in
     * both production and test contexts.</p>
     *
     * <p>All exits (success / LLM unavailable / hard failure) leave the submission in a
     * terminal status so the UI never stays stuck on AI_REVIEWING.</p>
     *
     * @param submissionId 【要审核的提交记录 ID】
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void runReview(Long submissionId) {
        var submission = submissions.findById(submissionId).orElse(null);
        if (submission == null) {
            // 【提交记录可能在异步调度前被删除，记录警告后直接返回】
            log.warn("AI review skipped: submission {} not found (deleted between submit and async fire?)", submissionId);
            return;
        }
        if (submission.status != SubmissionStatus.AI_REVIEWING) {
            // Already terminal (admin override, double-fire, etc.) — leave it alone.
            // 【已处于终态（管理员手动操作、重复触发等），不再处理】
            log.debug("AI review skipped: submission {} not in AI_REVIEWING (current={})", submissionId, submission.status);
            return;
        }
        var task = submission.task;

        try {
            var review = ai.review(task, submission);
            reviews.save(review);
            taskService.applyAiReview(task, submission, review);
        } catch (RuntimeException ex) {
            // 【LLM 调用失败时的降级处理：记录日志并生成软失败审核记录】
            log.warn("AI review failed for submission {}: {} — recording as NEED_MORE so the user can resubmit",
                    submissionId, ex.getMessage());
            // Synthesize a soft-fail review so the row reaches a terminal state. Marking
            // NEED_MORE (not REJECT) is the friendly choice — the failure is on our side,
            // not the user's, so don't penalize them with task rejection.
            // 【合成一个软失败审核记录，使记录达到终态。标记为 NEED_MORE（而非 REJECT）
            // 是更友好的选择——失败在我们这边，不应因系统问题惩罚用户。】
            var fallback = new AiReview();
            fallback.submission = submission;
            fallback.result = com.soulous.ai.AiReviewResult.NEED_MORE;
            fallback.score = 0;
            fallback.relevanceScore = 0;
            fallback.completenessScore = 0;
            fallback.qualityScore = 0;
            fallback.recommendedExp = 0;
            fallback.reason = "AI 审核服务暂时不可用，请稍后重新提交。";
            fallback.suggestion = "如果反复失败，请联系管理员检查 LLM 配置。";
            fallback.needManual = true;
            reviews.save(fallback);
            taskService.applyAiReview(task, submission, fallback);
        }
    }
}
