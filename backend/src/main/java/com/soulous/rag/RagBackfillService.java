package com.soulous.rag;

import com.soulous.aisession.PlanningSessionRepository;
import com.soulous.auth.UserAccount;
import com.soulous.goal.GoalRepository;
import com.soulous.task.TaskRepository;
import com.soulous.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【RAG 一次性回填服务】
 * 为单个用户重新索引所有 RAG 可用的历史数据。
 *
 * <p>存在的原因：当 RAG 首次启用（或切换嵌入提供商/维度后），
 * PlanningSessionService 和 TaskService 中的实时索引钩子只能捕获<em>新的</em>事件。
 * 本服务遍历用户已有的目标、已关闭的会话和已完成的任务，
 * 将它们通过 {@link RetrievalService} 的 upsert 推入索引。
 * 操作是幂等的——重复执行产生相同的最终状态。</p>
 *
 * <p>调用方提供用户对象；服务层调用方（通常是控制器）负责授权。
 * 当前控制器限制为仅允许用户操作自己的数据。</p>
 *
 * <p>English: One-shot reindex of all RAG-eligible artifacts for a single user.</p>
 *
 * <p>English: Why this exists: when RAG is enabled for the first time (or after switching embedding
 * provider/dimension), the live indexing hooks in PlanningSessionService and TaskService only
 * catch <em>new</em> events going forward. This service walks the user's existing goals,
 * closed sessions, and completed tasks and pushes them through {@link RetrievalService}
 * upserts. Idempotent — running twice produces the same end state.</p>
 *
 * <p>English: Caller-supplied user; service-layer caller (typically a controller) is responsible for
 * authorisation. The current controller restricts this to self.</p>
 */
@Service
public class RagBackfillService {
    private static final Logger log = LoggerFactory.getLogger(RagBackfillService.class);

    /** 【目标仓库】查询用户的所有目标，用于提取蒸馏记忆。 */
    private final GoalRepository goals;

    /** 【规划会话仓库】查询用户的所有会话，用于提取滚动摘要。 */
    private final PlanningSessionRepository sessions;

    /** 【任务仓库】查询用户的所有任务，用于提取已完成任务信息。 */
    private final TaskRepository tasks;

    /** 【检索服务】执行实际的向量索引操作。 */
    private final RetrievalService retrieval;

    /**
     * 【构造器】注入各数据仓库和检索服务。
     *
     * @param goals     目标数据仓库
     * @param sessions  规划会话数据仓库
     * @param tasks     任务数据仓库
     * @param retrieval RAG 检索服务
     */
    public RagBackfillService(GoalRepository goals, PlanningSessionRepository sessions,
                              TaskRepository tasks, RetrievalService retrieval) {
        this.goals = goals;
        this.sessions = sessions;
        this.tasks = tasks;
        this.retrieval = retrieval;
    }

    /**
     * 【重建用户全部 RAG 索引】
     * 遍历该用户的所有已知数据并重新索引。返回各类型的计数以便调用方向用户展示进度。
     * 当 RAG 禁用时为空操作（返回全零计数）。
     *
     * @param user 要重建索引的用户
     * @return 各类型索引计数的 Map（key: goalMemory / sessionSummary / completedTask）
     *
     * <p>English: Re-index everything we know about this user. Returns per-type counts so the caller can
     * surface progress to the user. No-op (returns zeros) when RAG is disabled.</p>
     */
    @Transactional
    public Map<String, Integer> reindexUser(UserAccount user) {
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("goalMemory", 0);
        counts.put("sessionSummary", 0);
        counts.put("completedTask", 0);
        if (!retrieval.isEnabled() || user == null) return counts;

        for (var goal : goals.findByUserOrderByUpdatedAtDesc(user)) {
            var snippet = goalSnippet(goal);
            if (snippet.isBlank()) continue;
            retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, goal.id, snippet);
            counts.merge("goalMemory", 1, Integer::sum);
        }

        for (var session : sessions.findByUserOrderByStartedAtDesc(user)) {
            if (session.runningSummary == null || session.runningSummary.isBlank()) continue;
            retrieval.indexOrUpdate(user, EmbeddingSourceType.SESSION_SUMMARY,
                    session.id, session.runningSummary);
            counts.merge("sessionSummary", 1, Integer::sum);
        }

        for (var task : tasks.findByUserOrderByCreatedAtDesc(user)) {
            if (task.status != TaskStatus.COMPLETED) continue;
            retrieval.indexCompletedTask(task);
            counts.merge("completedTask", 1, Integer::sum);
        }

        log.info("RAG backfill for user {}: {}", user.id, counts);
        return counts;
    }

    /**
     * 【构建目标摘要片段】
     * 使用与实时蒸馏钩子相同的格式，确保重新索引的行与实时索引的行保持一致。
     * 摘要包含"目标：标题"和蒸馏记忆 JSON 两部分。
     *
     * @param goal 目标实体
     * @return 格式化的摘要文本
     *
     * <p>English: Same snippet format the live distillation hook uses — keeps re-indexed rows consistent.</p>
     */
    private String goalSnippet(com.soulous.goal.Goal goal) {
        var sb = new StringBuilder();
        if (goal.title != null && !goal.title.isBlank()) sb.append("目标：").append(goal.title);
        if (goal.distilledMemoryJson != null && !goal.distilledMemoryJson.isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(goal.distilledMemoryJson);
        }
        return sb.toString();
    }
}
