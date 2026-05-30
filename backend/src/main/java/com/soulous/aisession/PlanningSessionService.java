package com.soulous.aisession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulous.ai.LlmService;
import com.soulous.auth.UserAccount;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.ModerationBlockedException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.goal.Goal;
import com.soulous.goal.GoalRepository;
import com.soulous.goal.GoalStatus;
import com.soulous.moderation.ModerationService;
import com.soulous.rag.EmbeddingSourceType;
import com.soulous.rag.RetrievalHit;
import com.soulous.rag.RetrievalService;
import com.soulous.task.Difficulty;
import com.soulous.task.StudyTask;
import com.soulous.task.TaskRepository;
import com.soulous.task.TaskStatus;
import com.soulous.task.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 【AI 规划会话核心业务服务：管理用户与 AI 学习教练之间的完整对话生命周期】
 *
 * <p>核心职责包括：
 * <ul>
 *   <li>新建目标 / 打卡跟进的会话创建</li>
 *   <li>消息发送（支持普通和 SSE 流式两种模式）</li>
 *   <li>LLM prompt 组装（分层结构：GOAL → MEMORY → RETRIEVED → PROGRESS → SUMMARY → RECENT → CURRENT）</li>
 *   <li>滚动摘要机制（超出窗口的早期对话自动压缩）</li>
 *   <li>PLAN_JSON 信封提取、计划编辑/确认/放弃</li>
 *   <li>会话关闭后的长期记忆蒸馏和 RAG 索引</li>
 *   <li>输入/输出内容安全审核</li>
 *   <li>重复目标检测</li>
 * </ul></p>
 */
@Service
public class PlanningSessionService {
    private static final Logger log = LoggerFactory.getLogger(PlanningSessionService.class);

    // Generous cap — the goal is to let the user iterate freely. Prompt size stays bounded by
    // the rolling-summary mechanism (older turns get folded into runningSummary), so a high
    // turn count doesn't translate to runaway token usage. Hard cap exists only as a safety
    // valve against pathological loops; we don't surface the count to the user.
    /** 【最大对话轮次上限（60 轮）：安全阀，防止极端循环；prompt 体积由滚动摘要机制控制，不会随轮次膨胀】 */
    private static final int MAX_TURNS = 60;
    /** 【最近轮次窗口大小：在 prompt 中保留原文的最近 4 轮对话，更早的轮次被折叠进滚动摘要】
     *  <p>English: Verbatim turns kept in-prompt. Anything older is folded into runningSummary.</p> */
    private static final int RECENT_TURN_WINDOW = 4;
    /** 【摘要批处理阈值：每当积累 4 轮新的旧对话时触发一次滚动摘要更新】
     *  <p>English: Re-summarize only when this many *new* old turns have accumulated since last summary.</p> */
    private static final int SUMMARY_BATCH = 4;
    /** 【滚动摘要最大字符数（1500 字）：防止摘要本身导致 prompt 膨胀】
     *  <p>English: Hard cap on runningSummary length — prevents prompt bloat.</p> */
    private static final int SUMMARY_MAX_CHARS = 1500;
    /** 【单条消息最大字符数（4000 字）：超长消息直接拒绝，防止 prompt 注入和 DoS 攻击】
     *  <p>English: Per-message length cap. Anything beyond gets rejected — prevents prompt-flooding & DoS.</p> */
    private static final int MAX_MESSAGE_CHARS = 4000;

    private final PlanningSessionRepository sessions;
    private final SessionTurnRepository turns;
    private final GoalRepository goals;
    private final TaskRepository tasks;
    private final LlmService llm;
    private final ModerationService moderation;
    private final RetrievalService retrieval;
    private final com.soulous.timetable.CourseEntryRepository courses;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 【输入审核拦截提示：用户消息未通过内容安全检查时的统一回复】 */
    private static final String BLOCKED_REPLY = "⚠️ 你的消息未通过内容安全检查，请调整后重试。如有疑问请联系管理员。";
    /** 【输出审核拦截提示：AI 回复未通过内容安全检查时的统一回复】 */
    private static final String OUTPUT_BLOCKED_REPLY = "（AI 回复已被安全系统拦截，请换一种方式提问。）";

    /**
     * 【构造函数：通过依赖注入组装所有必需的仓储和服务】
     *
     * @param sessions    【规划会话仓储】
     * @param turns       【会话轮次仓储】
     * @param goals       【目标仓储】
     * @param tasks       【任务仓储】
     * @param llm         【LLM 服务，负责与大语言模型交互】
     * @param moderation  【内容审核服务，负责输入/输出安全检查】
     * @param retrieval   【RAG 检索服务，负责向量检索历史记忆】
     * @param courses     【课表仓储，用于把用户所修课程作为 [COURSES] 背景喂给拆解 prompt】
     */
    public PlanningSessionService(PlanningSessionRepository sessions,
                                  SessionTurnRepository turns,
                                  GoalRepository goals,
                                  TaskRepository tasks,
                                  LlmService llm,
                                  ModerationService moderation,
                                  RetrievalService retrieval,
                                  com.soulous.timetable.CourseEntryRepository courses) {
        this.sessions = sessions;
        this.turns = turns;
        this.goals = goals;
        this.tasks = tasks;
        this.llm = llm;
        this.moderation = moderation;
        this.retrieval = retrieval;
        this.courses = courses;
    }

    // ----- entry points -------------------------------------------------

    /**
     * 【新建目标并开启规划会话：用户提交新学习目标后创建 Goal、打开会话、记录首轮对话并调用 AI 回复】
     *
     * <p>流程：校验输入 → 输入内容审核 → 重复目标检测 → 创建 Goal → 打开会话 → 追加用户轮次 → 调用 AI 生成首轮回复。</p>
     *
     * @param user     【当前登录用户】
     * @param goalText 【用户输入的目标描述文本】
     * @return 【会话视图，包含 AI 回复和重复目标候选列表】
     * @throws BadRequestException      【目标为空或超长】
     * @throws ModerationBlockedException【输入内容未通过安全审核】
     */
    @Transactional
    public SessionDtos.SessionView startNewGoal(UserAccount user, String goalText) {
        var clean = goalText == null ? "" : goalText.trim();
        if (clean.isBlank()) throw new BadRequestException("Goal cannot be empty");
        if (clean.length() > MAX_MESSAGE_CHARS) {
            throw new BadRequestException("Goal too long (max " + MAX_MESSAGE_CHARS + " chars)");
        }

        // --- Input moderation (no history yet for a brand-new goal) ---
        // Goal hasn't been persisted yet, so throwing is clean. 422 maps to a distinct error
        // shape on the client (with categories[]), letting the frontend show a tailored prompt.
        var inputCheck = moderation.moderateInput(user, clean, List.of(), null);
        if (inputCheck.blocked()) {
            throw new ModerationBlockedException(BLOCKED_REPLY, List.of(inputCheck.category().name()));
        }

        var duplicates = detectDuplicates(user, clean);

        var goal = new Goal();
        goal.user = user;
        goal.title = clean.length() > 200 ? clean.substring(0, 200) : clean;
        goal = goals.save(goal);

        var session = openSession(user, goal, SessionKind.NEW_GOAL);
        appendTurn(session, TurnRole.USER, clean);
        var assistant = runAssistantTurn(session, goal);
        return view(session, assistant, duplicates);
    }

    /**
     * 【打卡跟进：对已有目标开启 check-in 会话，如果已有活跃会话则复用】
     *
     * <p>流程：校验目标存在性和归属 → 校验目标状态（必须 ACTIVE 或 PAUSED）→ 查找已有活跃会话 →
     * 若有则直接返回；否则新建会话并调用 AI 生成开场回复。</p>
     *
     * @param user   【当前登录用户】
     * @param goalId 【目标 ID】
     * @return 【会话视图】
     * @throws NotFoundException    【目标不存在】
     * @throws ForbiddenException   【目标不属于当前用户】
     * @throws BadRequestException  【目标已不再活跃（已完成/已归档/已放弃）】
     */
    @Transactional
    public SessionDtos.SessionView startCheckIn(UserAccount user, Long goalId) {
        var goal = goals.findById(goalId).orElseThrow(() -> new NotFoundException("Goal not found"));
        if (!Objects.equals(goal.user.id, user.id)) throw new ForbiddenException("Goal belongs to another user");
        // Issue #5: ensureActive (called on every subsequent postMessage) rejects ABANDONED
        // AND ARCHIVED. Previously startCheckIn only rejected ABANDONED, so the user could
        // open a check-in against an archived/achieved goal, get a friendly first reply,
        // then hit "Goal is no longer active" on their second message. Align up-front so the
        // door is closed before the user types anything.
        if (goal.status != GoalStatus.ACTIVE && goal.status != GoalStatus.PAUSED) {
            throw new BadRequestException("Goal is no longer active");
        }

        var existing = sessions.findFirstByGoalAndStateInOrderByStartedAtDesc(
                goal, List.of(SessionState.DRAFTING, SessionState.PLAN_PROPOSED));
        if (existing.isPresent()) {
            return view(existing.get(), null, List.of());
        }

        var session = openSession(user, goal, SessionKind.CHECK_IN);
        var assistant = runAssistantTurn(session, goal);
        return view(session, assistant, List.of());
    }

    /**
     * 【发送消息（SSE 流式）：与 postMessage 相同的前置处理，但 AI 回复通过 onChunk 回调逐 token 推送】
     *
     * <p>Streaming variant of {@link #postMessage}. Same setup (input moderation, USER turn,
     * stale-plan reset), then runs the assistant turn with token-by-token delivery via
     * {@code onChunk}. Post-stream finalization (output moderation, plan envelope detection,
     * state transition, ASSISTANT turn persistence) happens in this thread before this
     * method returns the SessionView — callers (controllers) emit a final "done" event.</p>
     *
     * <p>If the default LLM provider doesn't support streaming (Mock / unavailable) or the
     * stream itself errors mid-flight, we fall back to the non-streaming reply and emit it
     * as a single chunk so the UI still updates correctly.</p>
     *
     * @param user      【当前登录用户】
     * @param sessionId 【会话 ID】
     * @param content   【用户消息内容】
     * @param onChunk   【token 回调：每收到一个增量文本片段时调用，用于 SSE 推送】
     * @return 【会话视图，包含最终 AI 回复】
     */
    @Transactional
    public SessionDtos.SessionView postMessageStream(UserAccount user, Long sessionId, String content,
                                                     java.util.function.Consumer<String> onChunk) {
        var session = loadOwned(user, sessionId);
        ensureActive(session);
        if (content == null || content.isBlank()) throw new BadRequestException("Message cannot be empty");
        var trimmed = content.trim();
        if (trimmed.length() > MAX_MESSAGE_CHARS) {
            throw new BadRequestException("Message too long (max " + MAX_MESSAGE_CHARS + " chars)");
        }

        var recentHistory = turns.findBySessionOrderByIdxAsc(session);
        var inputCheck = moderation.moderateInput(user, trimmed, recentHistory, session.id);
        if (inputCheck.blocked()) {
            appendTurn(session, TurnRole.USER, trimmed);
            appendTurn(session, TurnRole.ASSISTANT, BLOCKED_REPLY);
            onChunk.accept(BLOCKED_REPLY);
            return view(session, BLOCKED_REPLY, List.of());
        }

        if (session.state == SessionState.PLAN_PROPOSED) {
            session.pendingPlanJson = null;
            session.state = SessionState.DRAFTING;
        }

        appendTurn(session, TurnRole.USER, trimmed);
        var goal = session.goal;
        var assistant = runAssistantTurnStreaming(session, goal, onChunk);
        return view(session, assistant, List.of());
    }

    /**
     * 【发送消息（非流式）：用户在会话中发送一条消息，等待 AI 完整回复后返回】
     *
     * <p>流程：加载并校验会话归属 → 校验会话活跃状态 → 校验消息非空且不超长 →
     * 输入内容审核（带对话历史上下文）→ 若已提出计划则重置为起草态 → 追加用户轮次 →
     * 调用 AI 生成回复 → 输出内容审核 → 检测 PLAN_JSON 信封 → 追加助手轮次。</p>
     *
     * @param user      【当前登录用户】
     * @param sessionId 【会话 ID】
     * @param content   【用户消息内容】
     * @return 【会话视图，包含 AI 回复】
     */
    @Transactional
    public SessionDtos.SessionView postMessage(UserAccount user, Long sessionId, String content) {
        var session = loadOwned(user, sessionId);
        ensureActive(session);
        if (content == null || content.isBlank()) throw new BadRequestException("Message cannot be empty");
        var trimmed = content.trim();
        if (trimmed.length() > MAX_MESSAGE_CHARS) {
            throw new BadRequestException("Message too long (max " + MAX_MESSAGE_CHARS + " chars)");
        }

        // --- Input moderation with conversation context ---
        var recentHistory = turns.findBySessionOrderByIdxAsc(session);
        var inputCheck = moderation.moderateInput(user, trimmed, recentHistory, session.id);
        if (inputCheck.blocked()) {
            // Record the blocked attempt as a turn so history shows the rejection
            appendTurn(session, TurnRole.USER, trimmed);
            appendTurn(session, TurnRole.ASSISTANT, BLOCKED_REPLY);
            return view(session, BLOCKED_REPLY, List.of());
        }

        // If a plan was already proposed and the user is iterating, invalidate the stale draft.
        // Otherwise the user could commit an outdated plan that no longer reflects the conversation.
        if (session.state == SessionState.PLAN_PROPOSED) {
            // See issue #6: drop the stale draft so the user can't commit a plan that no
            // longer matches the conversation. Frontend disables the input box while the
            // user is editing pending tasks to prevent silent overwrite of hand edits.
            session.pendingPlanJson = null;
            session.state = SessionState.DRAFTING;
        }

        appendTurn(session, TurnRole.USER, trimmed);
        var goal = session.goal;
        var assistant = runAssistantTurn(session, goal);
        return view(session, assistant, List.of());
    }

    /**
     * 【编辑计划任务：用户在 PLAN_PROPOSED 状态下修改待确认计划中指定索引的任务字段】
     *
     * @param user      【当前登录用户】
     * @param sessionId 【会话 ID】
     * @param index     【任务在计划数组中的索引，从 0 开始】
     * @param patch     【补丁对象，仅非 null 字段被更新到任务中】
     * @return 【更新后的会话视图】
     * @throws BadRequestException 【索引越界或会话不处于可编辑状态】
     */
    @Transactional
    public SessionDtos.SessionView editPlanTask(UserAccount user, Long sessionId, int index,
                                                SessionDtos.EditPlanTaskRequest patch) {
        var session = loadOwned(user, sessionId);
        var arr = pendingTasksArrayForEdit(session);
        if (index < 0 || index >= arr.size()) throw new BadRequestException("Task index out of range");
        var task = (ObjectNode) arr.get(index);

        if (patch.title() != null) {
            var t = patch.title().trim();
            if (t.isBlank()) throw new BadRequestException("Title cannot be blank");
            task.put("title", t.length() > 128 ? t.substring(0, 128) : t);
        }
        if (patch.description() != null) task.put("description", patch.description());
        if (patch.estimatedMinutes() != null) {
            task.put("estimatedMinutes", clampInt(patch.estimatedMinutes(), 5, 240));
        }
        if (patch.difficulty() != null) {
            task.put("difficulty", parseEnum(Difficulty.class, patch.difficulty(), Difficulty.NORMAL).name());
        }
        if (patch.taskType() != null) {
            task.put("taskType", parseEnum(TaskType.class, patch.taskType(), TaskType.STUDY).name());
        }
        if (patch.baseExp() != null) task.put("baseExp", clampInt(patch.baseExp(), 5, 100));

        savePendingPlan(session, arr);
        return view(session, null, List.of());
    }

    /**
     * 【删除计划任务：用户在 PLAN_PROPOSED 状态下移除待确认计划中指定索引的任务】
     *
     * <p>计划至少保留一个任务，若只剩一个则拒绝删除（提示用户放弃整个会话）。</p>
     *
     * @param user      【当前登录用户】
     * @param sessionId 【会话 ID】
     * @param index     【任务在计划数组中的索引】
     * @return 【更新后的会话视图】
     * @throws BadRequestException 【索引越界或计划只剩一个任务】
     */
    @Transactional
    public SessionDtos.SessionView deletePlanTask(UserAccount user, Long sessionId, int index) {
        var session = loadOwned(user, sessionId);
        var arr = pendingTasksArrayForEdit(session);
        if (index < 0 || index >= arr.size()) throw new BadRequestException("Task index out of range");
        if (arr.size() <= 1) {
            throw new BadRequestException("Plan must keep at least one task; abandon the session to discard");
        }
        arr.remove(index);
        savePendingPlan(session, arr);
        return view(session, null, List.of());
    }

    /**
     * 【获取待编辑的任务数组：从待确认计划 JSON 中提取 tasks 数组的可变引用】
     *
     * <p>English: Returns the mutable tasks ArrayNode for a session that's in an editable plan state.</p>
     *
     * @param session 【会话实体，必须处于 PLAN_PROPOSED 状态】
     * @return 【任务数组节点，调用方可直接修改后通过 savePendingPlan 回写】
     * @throws BadRequestException 【会话不处于 PLAN_PROPOSED 状态或计划 JSON 为空/格式错误】
     */
    private ArrayNode pendingTasksArrayForEdit(PlanningSession session) {
        if (session.state != SessionState.PLAN_PROPOSED) {
            throw new BadRequestException("No pending plan to edit");
        }
        if (session.pendingPlanJson == null || session.pendingPlanJson.isBlank()) {
            throw new BadRequestException("Pending plan is empty");
        }
        JsonNode plan;
        try { plan = mapper.readTree(session.pendingPlanJson); }
        catch (Exception ex) { throw new BadRequestException("Pending plan is malformed"); }
        var tasksNode = plan.path("tasks");
        if (!tasksNode.isArray()) throw new BadRequestException("Pending plan has no tasks");
        // We attach the (possibly mutated) array back via re-serialization in savePendingPlan;
        // returning the live ArrayNode lets callers mutate it directly.
        return (ArrayNode) tasksNode;
    }

    /**
     * 【保存待确认计划：将修改后的任务数组回写到会话的 pendingPlanJson 字段并持久化】
     *
     * @param session 【会话实体】
     * @param tasks   【已修改的任务数组节点】
     * @throws BadRequestException 【JSON 解析或序列化失败】
     */
    private void savePendingPlan(PlanningSession session, ArrayNode tasks) {
        try {
            var root = (ObjectNode) mapper.readTree(session.pendingPlanJson);
            root.set("tasks", tasks);
            session.pendingPlanJson = mapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new BadRequestException("Failed to update plan");
        }
        sessions.save(session);
    }

    /**
     * 【确认计划：将 AI 提出的 PLAN_JSON 草案转化为实际的 StudyTask 记录并持久化到数据库】
     *
     * <p>流程：校验会话状态和计划非空 → 物化计划为任务记录 → 记录已确认任务 ID →
     * 状态流转为 COMMITTED → 设置结束时间 → 执行长期记忆蒸馏。</p>
     *
     * @param user      【当前登录用户】
     * @param sessionId 【会话 ID】
     * @return 【已提交状态的会话视图】
     * @throws BadRequestException 【会话不处于 PLAN_PROPOSED 或计划为空】
     */
    @Transactional
    public SessionDtos.SessionView commitPlan(UserAccount user, Long sessionId) {
        var session = loadOwned(user, sessionId);
        if (session.state != SessionState.PLAN_PROPOSED) {
            throw new BadRequestException("No plan to commit");
        }
        if (session.pendingPlanJson == null || session.pendingPlanJson.isBlank()) {
            throw new BadRequestException("Pending plan is empty");
        }

        var created = materializePlan(session);
        try {
            session.committedTaskIdsJson = mapper.writeValueAsString(created.stream().map(t -> t.id).toList());
        } catch (Exception e) {
            session.committedTaskIdsJson = "[]";
        }
        session.state = SessionState.COMMITTED;
        session.endedAt = LocalDateTime.now();
        sessions.save(session);

        closeWithDistillation(session);
        return view(session, null, List.of());
    }

    /**
     * 【放弃会话：用户主动放弃当前规划会话，状态流转为 ABANDONED】
     *
     * <p>若会话已处于 COMMITTED 或 CLOSED 状态则直接返回当前视图（幂等操作）。</p>
     *
     * @param user      【当前登录用户】
     * @param sessionId 【会话 ID】
     * @return 【会话视图】
     */
    @Transactional
    public SessionDtos.SessionView abandon(UserAccount user, Long sessionId) {
        var session = loadOwned(user, sessionId);
        if (session.state == SessionState.COMMITTED || session.state == SessionState.CLOSED) {
            return view(session, null, List.of());
        }
        session.state = SessionState.ABANDONED;
        session.endedAt = LocalDateTime.now();
        sessions.save(session);
        return view(session, null, List.of());
    }

    /**
     * 【获取单个会话详情（只读）：返回指定会话的完整信息和全部对话轮次】
     *
     * @param user      【当前登录用户】
     * @param sessionId 【会话 ID】
     * @return 【会话视图】
     */
    @Transactional(readOnly = true)
    public SessionDtos.SessionView get(UserAccount user, Long sessionId) {
        var session = loadOwned(user, sessionId);
        return view(session, null, List.of());
    }

    /**
     * 【查询目标下的会话列表（只读）：返回指定目标关联的全部会话摘要】
     *
     * @param user   【当前登录用户】
     * @param goalId 【目标 ID】
     * @return 【会话摘要列表，按开始时间降序排列】
     * @throws NotFoundException  【目标不存在】
     * @throws ForbiddenException 【目标不属于当前用户】
     */
    @Transactional(readOnly = true)
    public List<SessionDtos.SessionSummary> listForGoal(UserAccount user, Long goalId) {
        var goal = goals.findById(goalId).orElseThrow(() -> new NotFoundException("Goal not found"));
        if (!Objects.equals(goal.user.id, user.id)) throw new ForbiddenException("Goal belongs to another user");
        return sessions.findByGoalOrderByStartedAtDesc(goal).stream()
                .map(this::summarize)
                .toList();
    }

    /**
     * 【删除会话：删除非 COMMITTED 状态的会话及其全部对话轮次】
     *
     * <p>已确认的会话不能删除（保留历史记录），此限制防止用户误删有价值的计划数据。</p>
     *
     * @param user      【当前登录用户】
     * @param sessionId 【会话 ID】
     * @return 【删除结果，包含会话 ID 和被删除的轮次数】
     * @throws BadRequestException 【尝试删除已确认的会话】
     */
    @Transactional
    public SessionDtos.DeleteSessionResult deleteSession(UserAccount user, Long sessionId) {
        var session = loadOwned(user, sessionId);
        if (session.state == SessionState.COMMITTED) {
            throw new BadRequestException("Committed sessions cannot be deleted (preserves history)");
        }
        var turnList = turns.findBySessionOrderByIdxAsc(session);
        int deleted = turnList.size();
        turns.deleteBySession(session);
        sessions.delete(session);
        return new SessionDtos.DeleteSessionResult(session.id, deleted);
    }

    /**
     * 【构建会话摘要：从 PlanningSession 实体提取精简信息用于列表展示】
     *
     * @param session 【会话实体】
     * @return 【会话摘要 DTO】
     */
    private SessionDtos.SessionSummary summarize(PlanningSession session) {
        int committedCount = 0;
        if (session.committedTaskIdsJson != null && !session.committedTaskIdsJson.isBlank()) {
            try {
                var arr = mapper.readTree(session.committedTaskIdsJson);
                if (arr.isArray()) committedCount = arr.size();
            } catch (Exception ignored) {}
        }
        return new SessionDtos.SessionSummary(
                session.id, session.goal.id, session.kind, session.state, session.turnCount,
                session.startedAt, session.lastActivityAt, session.endedAt, committedCount);
    }

    /**
     * 【获取用户活跃目标列表（只读）：返回当前用户所有 ACTIVE 状态的目标】
     *
     * @param user 【当前登录用户】
     * @return 【活跃目标列表，按更新时间降序排列】
     */
    @Transactional(readOnly = true)
    public List<Goal> activeGoals(UserAccount user) {
        return goals.findByUserAndStatusOrderByUpdatedAtDesc(user, GoalStatus.ACTIVE);
    }

    /**
     * 【获取用户活跃目标列表（含任务进度，只读）：每个目标附带 totalTasks / completedTasks / openTasks 等统计】
     *
     * @param user 【当前登录用户】
     * @return 【目标信息 Map 列表】
     */
    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> activeGoalsWithProgress(UserAccount user) {
        return goals.findByUserAndStatusOrderByUpdatedAtDesc(user, GoalStatus.ACTIVE).stream()
                .map(g -> goalSummary(user, g))
                .toList();
    }

    /**
     * 【获取用户全部目标列表（含任务进度，只读）：返回所有目标（含已完成/已归档/已放弃）】
     *
     * @param user 【当前登录用户】
     * @return 【目标信息 Map 列表，按更新时间降序排列】
     */
    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> allGoalsWithProgress(UserAccount user) {
        return goals.findByUserOrderByUpdatedAtDesc(user).stream()
                .map(g -> goalSummary(user, g))
                .toList();
    }

    /**
     * 【构建单个目标的进度摘要：查询关联任务并统计完成/进行中数量】
     *
     * @param user 【当前登录用户】
     * @param g    【目标实体】
     * @return 【包含 id、title、status、targetDate、sessionCount、totalTasks、completedTasks、openTasks 等字段的 Map】
     */
    private java.util.Map<String, Object> goalSummary(UserAccount user, Goal g) {
        var related = tasks.findByUserAndGoalIdOrderByCreatedAtDesc(user, g.id);
        int total = related.size();
        int completed = (int) related.stream().filter(t -> t.status == com.soulous.task.TaskStatus.COMPLETED).count();
        int open = (int) related.stream().filter(t -> t.status != com.soulous.task.TaskStatus.COMPLETED).count();
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("id", g.id);
        map.put("title", g.title);
        map.put("status", g.status);
        map.put("targetDate", g.targetDate);
        map.put("sessionCount", g.sessionCount);
        map.put("lastSessionAt", g.lastSessionAt);
        map.put("updatedAt", g.updatedAt);
        map.put("totalTasks", total);
        map.put("completedTasks", completed);
        map.put("openTasks", open);
        return map;
    }

    // ----- core turn runner --------------------------------------------

    /**
     * 【AI 回复执行器（流式版）：组装 prompt 并调用 LLM 逐 token 流式生成回复】
     *
     * <p>Streaming version of {@link #runAssistantTurn}. Same orchestration, but the LLM call
     * is replaced with {@code llm.stream(...)} which fires {@code onChunk} per token.
     * Post-stream we re-run the same output-moderation / plan-extraction / persistence
     * steps. When the provider doesn't support streaming we emit the whole reply as one
     * chunk so the UI degrades gracefully.</p>
     *
     * <p>流式过程中通过 tracking Consumer 捕获已推送内容，若中途断连则保留部分内容并追加截断标记，
     * 避免用户看到半条回复后被静默替换为兜底文案。</p>
     *
     * @param session 【当前会话】
     * @param goal    【关联目标】
     * @param onChunk 【token 回调，用于 SSE 推送】
     * @return 【AI 回复的完整文本】
     */
    private String runAssistantTurnStreaming(PlanningSession session, Goal goal,
                                             java.util.function.Consumer<String> onChunk) {
        if (session.turnCount >= MAX_TURNS) {
            var forced = "（已达到对话上限，本次会话结束。请基于当前内容确认计划或重新开启新会话。）";
            appendTurn(session, TurnRole.ASSISTANT, forced);
            if (session.state != SessionState.PLAN_PROPOSED) {
                session.state = SessionState.CLOSED;
                session.endedAt = LocalDateTime.now();
                sessions.save(session);
            }
            onChunk.accept(forced);
            return forced;
        }

        var system = session.kind == SessionKind.NEW_GOAL
                ? newGoalSystemPrompt(goal)
                : checkInSystemPrompt(goal);
        maybeUpdateRunningSummary(session);
        var userPrompt = buildLayeredUserPrompt(session, goal);

        // Track what we've already streamed to the client so a mid-flight failure can
        // preserve the partial real content instead of silently replacing it with the
        // canned fallback. Otherwise the user sees half a real reply, then the whole
        // bubble flips to "(AI 暂不可用)" when `done` arrives — losing actual output.
        var captured = new StringBuilder();
        java.util.function.Consumer<String> tracking = chunk -> {
            if (chunk == null) return;
            captured.append(chunk);
            onChunk.accept(chunk);
        };

        String reply;
        try {
            if (llm.supportsStreaming()) {
                reply = llm.stream(system, userPrompt, tracking);
                if (reply == null || reply.isBlank()) {
                    reply = fallbackReply(session);
                    onChunk.accept(reply);
                }
            } else {
                // Provider doesn't actually stream — single-shot then flush as one chunk.
                reply = llm.complete(system, userPrompt)
                        .filter(s -> s != null && !s.isBlank())
                        .orElseGet(() -> fallbackReply(session));
                onChunk.accept(reply);
            }
        } catch (Exception ex) {
            log.warn("LLM stream failed for session {}: {}", session.id, ex.getMessage());
            if (captured.length() > 0) {
                // Keep whatever real content we got and append a visible truncation marker.
                // The persisted turn (and the bubble re-rendered after `done`) carries both,
                // so the user doesn't lose what the AI actually started saying.
                var marker = "\n\n（与 AI 的连接中断，以上为部分回复。可继续发送消息让 AI 接着说。）";
                onChunk.accept(marker);
                reply = captured + marker;
            } else {
                reply = fallbackReply(session);
                onChunk.accept(reply);
            }
        }

        var recentHistory = turns.findBySessionOrderByIdxAsc(session);
        var lastUserContent = recentHistory.isEmpty() ? ""
                : recentHistory.stream()
                    .filter(t -> t.role == TurnRole.USER)
                    .reduce((a, b) -> b)
                    .map(t -> t.content)
                    .orElse("");
        var outputCheck = moderation.moderateOutput(
                session.user, reply, lastUserContent, recentHistory, session.id);
        if (outputCheck.blocked()) {
            log.warn("Output blocked for session {}: {}", session.id, outputCheck.reason());
            reply = OUTPUT_BLOCKED_REPLY;
            // The streamed-out partial text was the unmoderated content. The frontend will
            // see the final SessionView with the moderated reply in the last turn and can
            // re-render the bubble. Honest tradeoff for streaming UX (see README).
        }

        var plan = extractPlanEnvelope(reply);
        if (plan != null && hasUsablePlanTasks(plan)) {
            session.pendingPlanJson = plan.toString();
            session.state = SessionState.PLAN_PROPOSED;
        } else if (plan != null) {
            log.warn("Session {} got a PLAN_JSON envelope with no usable tasks; ignoring envelope.", session.id);
        }

        appendTurn(session, TurnRole.ASSISTANT, reply);
        return reply;
    }

    /**
     * 【AI 回复执行器（非流式版）：组装 prompt 并调用 LLM 一次性生成完整回复】
     *
     * <p>流程：检查轮次上限 → 选择系统 prompt（新目标 vs 打卡跟进）→ 更新滚动摘要 →
     * 构建分层用户 prompt → 调用 LLM → 输出内容审核 → 提取 PLAN_JSON 信封 → 追加助手轮次。</p>
     *
     * <p>prompt 组装顺序（静态规则 → 目标锚点 → 长期记忆 → 数据库实际进展 → 滚动摘要 →
     * 最近原文 → 当前输入）将变化频率低的前缀放在前面，以最大化支持后端的 prompt 缓存命中率。</p>
     *
     * @param session 【当前会话】
     * @param goal    【关联目标】
     * @return 【AI 回复的完整文本】
     */
    private String runAssistantTurn(PlanningSession session, Goal goal) {
        if (session.turnCount >= MAX_TURNS) {
            var forced = "（已达到对话上限，本次会话结束。请基于当前内容确认计划或重新开启新会话。）";
            appendTurn(session, TurnRole.ASSISTANT, forced);
            // Terminate the session so further postMessage calls return a clear error instead of
            // looping forever on the same forced message.
            if (session.state != SessionState.PLAN_PROPOSED) {
                session.state = SessionState.CLOSED;
                session.endedAt = LocalDateTime.now();
                sessions.save(session);
            }
            return forced;
        }

        // Order matters: static rules → goal anchor → long-term memory → real DB state
        // → rolling summary → recent verbatim → current input. This grouping puts the
        // rarely-changing prefix first to maximise prompt-cache hit rate on supporting backends.
        var system = session.kind == SessionKind.NEW_GOAL
                ? newGoalSystemPrompt(goal)
                : checkInSystemPrompt(goal);

        maybeUpdateRunningSummary(session);

        var userPrompt = buildLayeredUserPrompt(session, goal);

        String reply;
        try {
            reply = llm.complete(system, userPrompt)
                    .filter(s -> s != null && !s.isBlank())
                    .orElseGet(() -> fallbackReply(session));
        } catch (Exception ex) {
            log.warn("LLM complete failed for session {}: {}", session.id, ex.getMessage());
            reply = fallbackReply(session);
        }

        // --- Output moderation: check LLM reply before showing to user ---
        var recentHistory = turns.findBySessionOrderByIdxAsc(session);
        var lastUserContent = recentHistory.isEmpty() ? ""
                : recentHistory.stream()
                    .filter(t -> t.role == TurnRole.USER)
                    .reduce((a, b) -> b)
                    .map(t -> t.content)
                    .orElse("");
        var outputCheck = moderation.moderateOutput(
                session.user, reply, lastUserContent, recentHistory, session.id);
        if (outputCheck.blocked()) {
            log.warn("Output blocked for session {}: {}", session.id, outputCheck.reason());
            reply = OUTPUT_BLOCKED_REPLY;
        }

        // Try to extract <PLAN_JSON>...</PLAN_JSON> envelope.
        // Issue #4: if the LLM hallucinates an empty/invalid tasks array, transitioning to
        // PLAN_PROPOSED would deadlock the session — commitPlan would always throw because
        // there's nothing to materialise, but no other code path can leave PLAN_PROPOSED
        // except postMessage's stale-draft drop. Validate up front and skip the transition
        // when there are no usable tasks; the user can simply continue the conversation.
        var plan = extractPlanEnvelope(reply);
        if (plan != null && hasUsablePlanTasks(plan)) {
            session.pendingPlanJson = plan.toString();
            session.state = SessionState.PLAN_PROPOSED;
        } else if (plan != null) {
            log.warn("Session {} got a PLAN_JSON envelope with no usable tasks; ignoring envelope.", session.id);
        }

        appendTurn(session, TurnRole.ASSISTANT, reply);
        return reply;
    }

    /**
     * 【构建分层用户 prompt：将对话上下文按标签段落组织，帮助模型独立关注各段信息】
     *
     * <p>English: Builds the user prompt as labeled sections. Each section starts with its own marker so
     * the model can attend to them independently — mitigates attention dilution that pure
     * back-and-forth transcripts suffer from after many turns.</p>
     *
     * <p>段落结构：[GOAL] 目标标题 → [MEMORY] 长期记忆 → [RETRIEVED] RAG 检索结果 →
     * [PROGRESS] 任务进展 → [SUMMARY] 早期对话滚动摘要 → [RECENT] 最近轮次 → [CURRENT] 当前输入。</p>
     *
     * @param session 【当前会话】
     * @param goal    【关联目标】
     * @return 【组装完成的用户 prompt 字符串】
     */
    private String buildLayeredUserPrompt(PlanningSession session, Goal goal) {
        var sb = new StringBuilder();

        sb.append("[GOAL]\n").append(safe(goal.title)).append("\n\n");

        sb.append("[MEMORY]\n");
        if (goal.distilledMemoryJson == null || goal.distilledMemoryJson.isBlank()) {
            sb.append("（暂无长期记忆）");
        } else {
            sb.append(goal.distilledMemoryJson);
        }
        sb.append("\n\n");

        sb.append("[COURSES]\n").append(buildCoursesSection(goal)).append("\n\n");

        sb.append("[RETRIEVED]\n").append(buildRetrievedSection(session, goal)).append("\n\n");

        sb.append("[PROGRESS]\n").append(summarizeProgress(goal)).append("\n\n");

        sb.append("[SUMMARY]\n");
        if (session.runningSummary == null || session.runningSummary.isBlank()) {
            sb.append("（本次会话尚无早期摘要）");
        } else {
            sb.append(session.runningSummary);
        }
        sb.append("\n\n");

        var recent = loadRecentWindow(session);
        sb.append("[RECENT]\n");
        if (recent.isEmpty()) {
            sb.append("（无）");
        } else {
            // The very last turn is presented separately under [CURRENT] so the model sees
            // a clear "what to respond to". Everything else is recent context.
            var last = recent.get(recent.size() - 1);
            var context = recent.subList(0, recent.size() - 1);
            if (context.isEmpty()) {
                sb.append("（无）");
            } else {
                for (var t : context) {
                    sb.append(t.role == TurnRole.USER ? "用户：" : "助手：")
                            .append(safe(t.content)).append('\n');
                }
            }
            sb.append("\n[CURRENT] (").append(last.role == TurnRole.USER ? "用户" : "助手").append(")\n");
            sb.append(safe(last.content));
        }
        return sb.toString();
    }

    /**
     * 【组装 [COURSES] 段：用户课表里的去重课程名，帮助 AI 了解其专业方向与学习背景。
     *  没有课表时返回占位说明。课表只是背景画像，不代表任务进度。】
     */
    private String buildCoursesSection(Goal goal) {
        if (goal == null || goal.user == null) return "（用户未导入课表）";
        var rows = courses.findByUserOrderByDayOfWeekAscStartSectionAsc(goal.user);
        if (rows.isEmpty()) return "（用户未导入课表）";
        var names = new java.util.LinkedHashSet<String>();
        for (var c : rows) {
            if (c.courseName != null && !c.courseName.isBlank()) names.add(c.courseName.trim());
        }
        if (names.isEmpty()) return "（用户未导入课表）";
        return "本学期所修课程：" + String.join("、", names);
    }

    /**
     * 【构建 RAG 检索段落：以用户最近一条消息为查询，从向量库检索相关历史并格式化为 prompt 段落】
     *
     * <p>English: Builds the [RETRIEVED] section using the user's most recent message as the query (or the
     * goal title at the very start of a session). Each hit is rendered with its source label
     * and similarity score so the model can decide how much weight to give it. RAG-disabled
     * (or empty result) returns a "（暂无相关历史）" placeholder so the section is always present
     * — that helps the prompt-cache prefix stay stable across turns.</p>
     *
     * @param session 【当前会话】
     * @param goal    【关联目标】
     * @return 【格式化的检索结果文本】
     */
    private String buildRetrievedSection(PlanningSession session, Goal goal) {
        if (!retrieval.isEnabled()) return "（未启用）";
        var query = lastUserContent(session);
        if (query == null || query.isBlank()) query = goal.title;
        if (query == null || query.isBlank()) return "（暂无相关历史）";

        List<RetrievalHit> hits;
        try {
            hits = retrieval.retrieve(session.user, query);
        } catch (Exception ex) {
            log.warn("RAG retrieve failed for session {}: {}", session.id, ex.getMessage());
            return "（检索失败，跳过）";
        }
        if (hits.isEmpty()) return "（暂无相关历史）";

        var sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            if (i > 0) sb.append("\n---\n");
            sb.append("[").append(i + 1).append("] ")
                    .append(hitLabel(h.sourceType()))
                    .append(" (相似度 ").append(String.format(Locale.ROOT, "%.2f", h.similarity())).append(")\n");
            sb.append(truncate(safe(h.content()), 500));
        }
        return sb.toString();
    }

    /**
     * 【检索命中来源标签：将 EmbeddingSourceType 映射为中文显示标签】
     *
     * @param t 【嵌入来源类型】
     * @return 【中文标签：过往目标记忆 / 过往对话摘要 / 已完成任务 / 过往每日复盘】
     */
    private String hitLabel(EmbeddingSourceType t) {
        return switch (t) {
            case GOAL_MEMORY -> "过往目标记忆";
            case SESSION_SUMMARY -> "过往对话摘要";
            case COMPLETED_TASK -> "已完成任务";
            case DAILY_REVIEW -> "过往每日复盘";
        };
    }

    /**
     * 【获取最后一条用户消息：从会话轮次中倒序查找最近的 USER 角色消息】
     *
     * @param session 【当前会话】
     * @return 【用户消息内容，若无用户消息则返回 null】
     */
    private String lastUserContent(PlanningSession session) {
        var all = turns.findBySessionOrderByIdxAsc(session);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).role == TurnRole.USER) return all.get(i).content;
        }
        return null;
    }

    /**
     * 【安全截断：将字符串截断到指定最大长度，超长部分替换为省略号】
     *
     * @param s   【原始字符串，可为 null】
     * @param max 【最大允许长度】
     * @return 【截断后的字符串，null 输入返回空串】
     */
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * 【加载最近对话窗口：获取最新 12 条轮次，过滤已摘要的旧轮次，返回窗口内的最近 N 条】
     *
     * <p>先从数据库取最新 12 条（降序），反转为时间正序，过滤掉已被滚动摘要折叠的轮次
     * （idx < summarizedUpToIdx），然后截取最后 RECENT_TURN_WINDOW 条作为最终窗口。</p>
     *
     * @param session 【当前会话】
     * @return 【最近窗口内的轮次列表】
     */
    private List<SessionTurn> loadRecentWindow(PlanningSession session) {
        var recent = turns.findTop12BySessionOrderByIdxDesc(session);
        Collections.reverse(recent);
        // Drop anything already folded into runningSummary.
        var filtered = new ArrayList<SessionTurn>(recent.size());
        for (var t : recent) {
            if (t.idx >= session.summarizedUpToIdx) filtered.add(t);
        }
        int from = Math.max(0, filtered.size() - RECENT_TURN_WINDOW);
        return new ArrayList<>(filtered.subList(from, filtered.size()));
    }

    // ----- rolling summary ---------------------------------------------

    /**
     * 【滚动摘要更新器：当积累足够多的旧轮次时，调用 LLM 将其压缩进 runningSummary】
     *
     * <p>English: If at least {@link #SUMMARY_BATCH} turns sit *older* than the recent window AND those
     * turns haven't yet been folded into {@link PlanningSession#runningSummary}, compress them
     * with one LLM call and advance {@link PlanningSession#summarizedUpToIdx}. Silent no-op
     * when LLM is unavailable or the call fails — we just keep the prior summary.</p>
     *
     * <p>算法：加载全部轮次 → 计算 cutoffExclusive（最后 RECENT_TURN_WINDOW 条保持原文）→
     * 筛选出尚未摘要且在窗口外的轮次 → 若不足 SUMMARY_BATCH 则跳过 → 否则拼装摘要 prompt
     * 并调用 LLM 压缩 → 更新 runningSummary 和 summarizedUpToIdx。</p>
     *
     * @param session 【当前会话】
     */
    private void maybeUpdateRunningSummary(PlanningSession session) {
        if (!llm.isAvailable()) return;
        var all = turns.findBySessionOrderByIdxAsc(session);
        if (all.size() <= RECENT_TURN_WINDOW) return;
        var cutoffExclusive = all.size() - RECENT_TURN_WINDOW; // first RECENT_TURN_WINDOW from end stay verbatim
        var pending = new ArrayList<SessionTurn>();
        for (var t : all) {
            if (t.idx < session.summarizedUpToIdx) continue;        // already summarized
            if (recentIndex(all, t.idx) < cutoffExclusive) pending.add(t);
        }
        if (pending.size() < SUMMARY_BATCH) return;

        var newest = pending.get(pending.size() - 1);
        var transcript = new StringBuilder();
        for (var t : pending) {
            transcript.append(t.role == TurnRole.USER ? "用户：" : "助手：")
                    .append(safe(t.content)).append('\n');
        }
        var prior = session.runningSummary == null ? "（无）" : session.runningSummary;
        var userPrompt = "旧摘要：\n" + prior + "\n\n新增对话片段：\n" + transcript;

        try {
            var fresh = llm.complete(summarySystemPrompt(), userPrompt).orElse(null);
            if (fresh == null || fresh.isBlank()) return;
            if (fresh.length() > SUMMARY_MAX_CHARS) fresh = fresh.substring(0, SUMMARY_MAX_CHARS);
            session.runningSummary = fresh;
            session.summarizedUpToIdx = newest.idx + 1;
            sessions.save(session);
        } catch (Exception ex) {
            log.warn("Rolling-summary update failed for session {}: {}", session.id, ex.getMessage());
        }
    }

    /**
     * 【查找轮次在全部列表中的位置索引：将全局 idx 映射为列表中的下标位置】
     *
     * @param all 【全部轮次列表】
     * @param idx 【轮次的全局序号】
     * @return 【在列表中的下标位置，未找到返回 -1】
     */
    private int recentIndex(List<SessionTurn> all, int idx) {
        for (int i = 0; i < all.size(); i++) if (all.get(i).idx == idx) return i;
        return -1;
    }

    /**
     * 【滚动摘要系统 prompt：指导 LLM 将旧摘要和新增对话片段整合为一份紧凑的新摘要】
     *
     * <p>规则：完整重写（不追加）→ 不超过 800 字 → 保留关键决策/约束/偏好/未解疑问 →
     * 第三人称叙述 → 以新陈述为准 → 只输出摘要正文。</p>
     *
     * @return 【系统 prompt 字符串】
     */
    private String summarySystemPrompt() {
        return """
                你是对话压缩助手。把「旧摘要 + 新增对话片段」整合为一份紧凑的新摘要。

                规则：
                - 完整重写，不要追加到旧摘要后面。
                - 不超过 800 字。
                - 保留用户给出的关键决策、约束、偏好、未解疑问。
                - 用第三人称叙述（"用户表示…"，"助手提出…"），不要复述原对话。
                - 若用户在新片段中纠正了旧陈述，以新陈述为准。
                - 只输出摘要正文，不要任何解释、Markdown 标记或前后缀。
                """;
    }

    // ----- prompts ------------------------------------------------------

    /**
     * 【新目标系统 prompt：指导 AI 学习教练在用户首次提出目标时的对话行为】
     *
     * <p>核心职责：将目标拆解为 3-7 个可在学习打卡 App 中执行的具体任务。
     * 行为规则：最多问 2 个澄清问题 → 用户说"直接给计划"则立即输出 →
     * 不要求承诺 → 掌握足够信息即输出 PLAN_JSON 草案。</p>
     *
     * @param goal 【目标实体，其标题会嵌入 prompt 中】
     * @return 【系统 prompt 字符串】
     */
    private String newGoalSystemPrompt(Goal goal) {
        return """
                你是用户的学习教练。你唯一的职责是：把目标"%s"拆解成可在「学习打卡」App 中执行的具体任务。

                输入结构（你将在用户消息里看到以下段落，请分别理解）：
                  [GOAL]     用户最初提出的目标原句（注意力锚点，回答时要始终围绕它）
                  [MEMORY]   该目标的长期记忆 JSON（可能为空）
                  [COURSES]  用户本学期所修课程（帮助你了解其专业方向与学习背景，可据此调整任务难度/示例，但不要假设课程进度）
                  [PROGRESS] 任务系统数据库中的实际进展（权威）
                  [SUMMARY]  早期对话的压缩摘要（可能为空）
                  [RECENT]   最近若干轮原文对话
                  [CURRENT]  用户当前这条发言（最需要你直接回应的内容）

                行为规则：
                1. 先判断信息是否够拆解：只有当「当前水平 / 每日可投入时间 / 使用的工具或语言」里存在会实质影响任务难度与编排、又无法从 [GOAL]/[MEMORY]/[RECENT] 推断的关键空白时，才需要澄清。目标本身已足够具体，就跳过提问、直接给 PLAN_JSON。
                2. 需要澄清时，用 CLARIFY_JSON 信封提问，绝不要用纯文字问；最多 2 道、每道 2–4 个选项。每个选项都要是用户能直接点选的完整答案（如"零基础""每天 1 小时以上"），不要给"看情况"这类无效项。不要重复问 [MEMORY]/[RECENT] 里用户已经回答过的内容。
                3. 如果用户说「直接给我计划」，立即给 PLAN_JSON，不再追问。
                4. 不要要求用户写「承诺」「保证」「我承诺…」这类话——你的工作是拆任务，不是要承诺。
                5. 计划必须有 3–7 个任务，每个任务字段：title（≤40 字）、description、estimatedMinutes（15–90）、difficulty（EASY/NORMAL/HARD）、taskType（STUDY/CODING/NOTE/MEMORY/REVIEW/SIMPLE）。任务要贴合用户已知的水平与时间、由易到难循序渐进。
                6. 一条回复里 CLARIFY_JSON 与 PLAN_JSON 二选一，不要边问边给：还在澄清就只给 CLARIFY_JSON；信息够了就直接在末尾给 PLAN_JSON，不必等用户"明确确认"——用户会在 UI 看到可编辑卡片自行决定是否落地。

                需要澄清时，输出 CLARIFY_JSON 的格式（每题 2–4 个 options，单选用 multiSelect:false，多选用 true）：

                <CLARIFY_JSON>
                {"questions":[{"id":"tool","question":"你打算用什么工具或语言？","multiSelect":false,"options":[{"label":"Python","hint":"适合数据/AI"},{"label":"JavaScript"}]},{"id":"time","question":"每天大约能投入多少时间？","multiSelect":false,"options":[{"label":"30 分钟内"},{"label":"30–60 分钟"},{"label":"1 小时以上"}]}]}
                </CLARIFY_JSON>

                信息足够、可以拆解时，输出 PLAN_JSON 的格式：

                <PLAN_JSON>
                {"goalTitle":"...","tasks":[{"title":"...","description":"...","estimatedMinutes":30,"difficulty":"NORMAL","taskType":"STUDY"}]}
                </PLAN_JSON>

                重要：你"问问题"必须通过 <CLARIFY_JSON>...</CLARIFY_JSON>、"给计划"必须通过 <PLAN_JSON>...</PLAN_JSON>，且必须真的输出对应标签包裹的合法 JSON——绝不能只在文字里声称"已生成 / 我问你几个问题"却不输出对应信封。标签外可有简短自然语言说明，但 JSON 必须完整。
                """.formatted(safe(goal.title));
    }

    /**
     * 【打卡跟进系统 prompt：指导 AI 学习教练在用户对已有目标进行 check-in 时的对话行为】
     *
     * <p>核心职责：基于当前进展给出可执行的下一步任务清单。
     * 行为规则：先肯定进展再诊断阻塞 → 提议方向（继续/调整/达成/暂停）→ 不要求承诺 →
     * 掌握足够信息即输出 PLAN_JSON 草案（含可选的 goalStatusChange 字段）。</p>
     *
     * @param goal 【目标实体】
     * @return 【系统 prompt 字符串】
     */
    private String checkInSystemPrompt(Goal goal) {
        return """
                你是用户的学习教练，正在跟进用户的一个已有目标"%s"。你的核心职责是：基于当前进展，给出可执行的下一步任务清单。

                输入结构（你将在用户消息里看到以下段落，请分别理解）：
                  [GOAL]     目标标题（注意力锚点）
                  [MEMORY]   目标的长期记忆 JSON（用户偏好、约束、里程碑 — 仅作意图参考）
                  [COURSES]  用户本学期所修课程（帮助你了解其专业方向与学习背景，可据此调整任务难度/示例，但不要假设课程进度）
                  [PROGRESS] 任务系统中的实际进展（来自数据库，是「实际发生」的权威来源；若与对话冲突，以此为准）
                  [SUMMARY]  本次会话早期对话的压缩摘要（若已超过窗口）
                  [RECENT]   最近若干轮原文对话
                  [CURRENT]  用户当前发言（直接回应它）

                行为规则：
                1. 开场先用一句话肯定 [PROGRESS] 中的实际进展。
                2. 若当前阻塞或下一步方向无法从 [PROGRESS]/[RECENT] 判断、且影响如何排任务，就用 CLARIFY_JSON 信封提问（≤2 道、每道 2–4 个可直接点选的完整选项，如"时间不够""概念没吃透"），绝不要用纯文字问；方向已清楚则跳过提问。
                3. 可提议方向：(a) 继续当前计划；(b) 调整未来 1–2 周；(c) 标记目标已达成；(d) 暂停目标。
                4. 不要要求用户写「承诺」「保证」之类的话——你的工作是拆任务，不是要承诺。
                5. 一条回复里 CLARIFY_JSON 与 PLAN_JSON 二选一，不要边问边给：还在诊断就只给 CLARIFY_JSON；信息够了就直接给 PLAN_JSON 草案，不必等用户「明确确认」——用户会在 UI 看到可编辑卡片自行决定是否落地。

                需要澄清时，输出 CLARIFY_JSON 的格式（每题 2–4 个 options，单选用 multiSelect:false，多选用 true）：

                <CLARIFY_JSON>
                {"questions":[{"id":"blocker","question":"目前主要卡在哪一步？","multiSelect":false,"options":[{"label":"时间不够"},{"label":"概念没吃透"},{"label":"缺乏练习反馈"}]}]}
                </CLARIFY_JSON>

                信息足够时，输出 PLAN_JSON 的格式：

                <PLAN_JSON>
                {"goalTitle":"...","tasks":[...],"goalStatusChange":"ACHIEVED|PAUSED|null"}
                </PLAN_JSON>

                重要：你"问问题"必须通过 <CLARIFY_JSON>...</CLARIFY_JSON>、"给计划"必须通过 <PLAN_JSON>...</PLAN_JSON>，且必须真的输出对应标签包裹的合法 JSON——绝不能只在文字里声称却不输出对应信封。标签外可有简短自然语言说明，但 JSON 必须完整。
                """.formatted(safe(goal.title));
    }

    /**
     * 【长期记忆蒸馏系统 prompt：指导 LLM 将旧记忆、对话记录和任务进展整合为新的目标记忆 JSON】
     *
     * <p>规则：完整重写（不追加）→ 不超过 2000 字符 → 以新陈述为准 → 保留未撤回的 successCriteria →
     * 只输出 JSON 本身。Schema 包含 goalStatement、successCriteria、currentLevel、constraints、
     * preferences、openQuestions、milestones、lastSessionSummary 等字段。</p>
     *
     * @return 【系统 prompt 字符串】
     */
    private String distillationSystemPrompt() {
        return """
                你的任务是把「旧的目标记忆 JSON + 本次对话记录 + 任务系统新增完成项」整合为新的目标记忆 JSON。

                规则：
                - 完整重写，不要在旧 JSON 上追加。
                - 总长度不超过 2000 字符。
                - 如用户在对话中纠正了旧陈述，以新陈述为准。
                - 未被显式撤回的 successCriteria 必须保留。
                - 只输出 JSON 本身，不要任何解释、Markdown 围栏。

                Schema：
                {
                  "version": 1,
                  "goalStatement": "...",
                  "successCriteria": ["..."],
                  "currentLevel": {"selfReported": "...", "inferredFromTasks": "..."},
                  "constraints": {"timePerWeek": "...", "tools": ["..."], "priorAttempts": "..."},
                  "preferences": {"taskSize": "...", "style": "...", "commitmentLevel": "..."},
                  "openQuestions": ["..."],
                  "milestones": [{"label":"...","status":"todo|in_progress|done","updatedAt":"YYYY-MM-DD"}],
                  "lastSessionSummary": "..."
                }
                """;
    }

    // ----- duplicate detection -----------------------------------------

    /**
     * 【重复目标检测：判断用户的新目标是否与已有 ACTIVE 目标在本质上指同一件事】
     *
     * <p>策略：若 LLM 不可用则回退到简单的标题包含匹配；否则调用 LLM 进行语义判断，
     * 返回最多 3 个重复候选及其判定原因。检测失败时静默返回空列表，不影响主流程。</p>
     *
     * @param user        【当前登录用户】
     * @param newGoalText 【新目标描述文本】
     * @return 【重复候选列表，最多 3 项】
     */
    private List<SessionDtos.DuplicateCandidate> detectDuplicates(UserAccount user, String newGoalText) {
        var existing = goals.findByUserAndStatusOrderByUpdatedAtDesc(user, GoalStatus.ACTIVE);
        if (existing.isEmpty()) return List.of();
        if (!llm.isAvailable()) {
            return existing.stream()
                    .filter(g -> looksSimilar(g.title, newGoalText))
                    .limit(3)
                    .map(g -> new SessionDtos.DuplicateCandidate(g.id, g.title, "标题相似"))
                    .toList();
        }
        var listed = new StringBuilder();
        for (int i = 0; i < existing.size(); i++) {
            listed.append(i + 1).append(". [id=").append(existing.get(i).id).append("] ")
                    .append(safe(existing.get(i).title)).append('\n');
        }
        var system = "判断用户的新目标是否与任一已有目标在本质上指同一件事（同一学科、同一项目、同一明确的产物）。" +
                "只输出 JSON：{\"duplicates\":[{\"goalId\":<id>,\"reason\":\"<中文一句>\"}]}。若无重复，duplicates 为空数组。";
        var prompt = "已有目标列表：\n" + listed + "\n新目标：" + newGoalText;
        try {
            // Validated: the answer must carry a `duplicates` array (empty array = "no dupes" is fine).
            // A malformed reply triggers one corrective retry before we fall back to no-dupes.
            var json = llm.completeJsonValidated(system, prompt,
                    j -> j != null && j.path("duplicates").isArray()).orElse(null);
            if (json == null) return List.of();
            var arr = json.path("duplicates");
            if (!arr.isArray()) return List.of();
            var out = new ArrayList<SessionDtos.DuplicateCandidate>();
            for (var item : arr) {
                var id = item.path("goalId").asLong(0);
                if (id <= 0) continue;
                var match = existing.stream().filter(g -> Objects.equals(g.id, id)).findFirst().orElse(null);
                if (match == null) continue;
                out.add(new SessionDtos.DuplicateCandidate(match.id, match.title, item.path("reason").asText("")));
                if (out.size() >= 3) break;
            }
            return out;
        } catch (Exception ex) {
            log.warn("Duplicate detection failed: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 【简单标题相似度检查：当 LLM 不可用时的回退策略，基于字符串包含关系判断】
     *
     * @param a 【标题 A】
     * @param b 【标题 B】
     * @return 【若一个标题包含另一个（忽略大小写和空白）则返回 true】
     */
    private boolean looksSimilar(String a, String b) {
        if (a == null || b == null) return false;
        var x = a.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        var y = b.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (x.isBlank() || y.isBlank()) return false;
        return x.contains(y) || y.contains(x);
    }

    // ----- plan envelope + materialization -----------------------------

    // ----- plan envelope + materialization -----------------------------

    /**
     * 【验证计划信封有效性：检查 PLAN_JSON 中的 tasks 数组是否至少有一个标题非空的任务】
     *
     * <p>English: True when the envelope's {@code tasks} array has at least one entry with a non-blank
     * title — i.e. {@link #materializePlan} would actually create something. We pre-check
     * here so a hallucinated empty plan never transitions the session into a state that
     * can't be committed.</p>
     *
     * @param plan 【已解析的 PLAN_JSON 树节点】
     * @return 【若存在至少一个有效任务则返回 true】
     */
    private static boolean hasUsablePlanTasks(JsonNode plan) {
        if (plan == null) return false;
        var arr = plan.path("tasks");
        if (!arr.isArray() || arr.isEmpty()) return false;
        for (var node : arr) {
            var title = node.path("title").asText("");
            if (title != null && !title.isBlank()) return true;
        }
        return false;
    }

    /**
     * 【提取 PLAN_JSON 信封：从 AI 回复文本中解析 &lt;PLAN_JSON&gt;...&lt;/PLAN_JSON&gt; 标签包裹的 JSON】
     *
     * @param reply 【AI 回复完整文本】
     * @return 【解析后的 JSON 树节点，若未找到标签或解析失败则返回 null】
     */
    private JsonNode extractPlanEnvelope(String reply) {
        if (reply == null) return null;
        var open = reply.indexOf("<PLAN_JSON>");
        var close = reply.indexOf("</PLAN_JSON>");
        if (open < 0 || close <= open) return null;
        var inner = reply.substring(open + "<PLAN_JSON>".length(), close).trim();
        try {
            return mapper.readTree(inner);
        } catch (Exception ex) {
            log.warn("Plan envelope parse failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 【提取 CLARIFY_JSON 信封：从 AI 回复文本中解析 &lt;CLARIFY_JSON&gt;...&lt;/CLARIFY_JSON&gt; 标签
     * 包裹的结构化澄清问题 JSON。与 {@link #extractPlanEnvelope} 同构——让 AI 用可点选的选项卡向用户
     * 提问，而不是纯文字一问一答。解析失败或无标签时返回 null。】
     *
     * @param reply 【AI 回复完整文本】
     * @return 【解析后的 JSON 树节点（形如 {"questions":[...]}），无标签或解析失败返回 null】
     */
    private JsonNode extractClarifyEnvelope(String reply) {
        if (reply == null) return null;
        var open = reply.indexOf("<CLARIFY_JSON>");
        var close = reply.indexOf("</CLARIFY_JSON>");
        if (open < 0 || close <= open) return null;
        var inner = reply.substring(open + "<CLARIFY_JSON>".length(), close).trim();
        try {
            return mapper.readTree(inner);
        } catch (Exception ex) {
            log.warn("Clarify envelope parse failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 【验证澄清信封有效性：questions 数组非空，且至少有一个问题带 question 文本和非空 options 数组。
     * 信封不可用时（如模型幻觉出空问题）调用方应忽略它、当作普通 prose 处理。】
     *
     * @param clarify 【已解析的 CLARIFY_JSON 树节点】
     * @return 【存在至少一个可用问题则返回 true】
     */
    private static boolean hasUsableClarify(JsonNode clarify) {
        if (clarify == null) return false;
        var arr = clarify.path("questions");
        if (!arr.isArray() || arr.isEmpty()) return false;
        for (var q : arr) {
            var hasText = !q.path("question").asText("").isBlank();
            var opts = q.path("options");
            if (hasText && opts.isArray() && !opts.isEmpty()) return true;
        }
        return false;
    }

    /**
     * 【取最新一条 ASSISTANT 轮次的原文内容，供 view() 重新抽取澄清信封——
     * 澄清问题随 assistant turn 存库，无需额外列。无 assistant 轮次时返回 null。】
     */
    private String latestAssistantContent(PlanningSession session) {
        var all = turns.findBySessionOrderByIdxAsc(session);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).role == TurnRole.ASSISTANT) return all.get(i).content;
        }
        return null;
    }

    /**
     * 【物化计划：将 pendingPlanJson 中的任务清单转化为实际的 StudyTask 记录并保存到数据库】
     *
     * <p>流程：解析 pendingPlanJson → 遍历 tasks 数组 → 为每个有效任务创建 StudyTask 实体 →
     * 设置 title、description、taskType、difficulty、estimatedMinutes、baseExp、status=TODO、goalId →
     * 批量保存（最多 8 个任务）→ 处理 goalStatusChange（ACHIEVED/PAUSED）→ 更新目标时间戳。</p>
     *
     * <p>注意：目标标题以用户输入为准，不允许 LLM 静默改写；仅状态变更从计划信封中读取。</p>
     *
     * @param session 【当前会话，必须处于 PLAN_PROPOSED 状态】
     * @return 【新创建的任务列表】
     * @throws BadRequestException 【计划 JSON 格式错误或无有效任务】
     */
    private List<StudyTask> materializePlan(PlanningSession session) {
        JsonNode plan;
        try {
            plan = mapper.readTree(session.pendingPlanJson);
        } catch (Exception ex) {
            throw new BadRequestException("Pending plan is malformed");
        }

        var tasksJson = plan.path("tasks");
        if (!tasksJson.isArray() || tasksJson.isEmpty()) {
            throw new BadRequestException("Plan has no tasks to commit");
        }

        var goal = session.goal;
        var created = new ArrayList<StudyTask>();
        for (var node : tasksJson) {
            var title = node.path("title").asText("").trim();
            if (title.isBlank()) continue;
            var task = new StudyTask();
            task.user = session.user;
            task.title = title.length() > 128 ? title.substring(0, 128) : title;
            task.description = node.path("description").asText("");
            task.taskType = parseEnum(TaskType.class, node.path("taskType").asText("STUDY"), TaskType.STUDY);
            task.difficulty = parseEnum(Difficulty.class, node.path("difficulty").asText("NORMAL"), Difficulty.NORMAL);
            task.estimatedMinutes = clampInt(node.path("estimatedMinutes").asInt(30), 5, 240);
            task.baseExp = clampInt(node.path("baseExp").asInt(20), 5, 100);
            task.status = TaskStatus.TODO;
            task.goalId = goal.id;
            created.add(task);
            if (created.size() >= 8) break;
        }
        if (created.isEmpty()) {
            throw new BadRequestException("Plan has no valid tasks to commit");
        }
        tasks.saveAll(created);

        // Goal title is what the user typed — do NOT let the LLM silently rewrite it.
        // Only the status transition (ACHIEVED/PAUSED) is honored from the plan envelope.
        var statusChange = plan.path("goalStatusChange").asText("");
        if ("ACHIEVED".equalsIgnoreCase(statusChange)) goal.status = GoalStatus.ACHIEVED;
        else if ("PAUSED".equalsIgnoreCase(statusChange)) goal.status = GoalStatus.PAUSED;
        goal.updatedAt = LocalDateTime.now();
        goals.save(goal);

        return created;
    }

    // ----- distillation -------------------------------------------------

    /**
     * 【会话关闭蒸馏：会话确认后将对话内容蒸馏为目标的长期记忆并更新 RAG 索引】
     *
     * <p>流程：若 LLM 不可用则仅更新目标的 sessionCount 和 lastSessionAt →
     * 渲染完整对话记录 → 调用 LLM 蒸馏为 JSON → 校验长度（≤4000 字符）→
     * 更新 goal.distilledMemoryJson → 更新目标时间戳 → 将目标记忆和会话摘要写入 RAG 语料库。</p>
     *
     * <p>若蒸馏输出超过 4KB，保留上一版记忆并设置 distillationWarning 提示用户。</p>
     *
     * @param session 【当前会话】
     */
    private void closeWithDistillation(PlanningSession session) {
        var goal = session.goal;
        if (!llm.isAvailable()) {
            goal.sessionCount += 1;
            goal.lastSessionAt = LocalDateTime.now();
            goal.updatedAt = LocalDateTime.now();
            goals.save(goal);
            return;
        }
        var transcript = renderTranscript(session);
        var prior = goal.distilledMemoryJson == null ? "{}" : goal.distilledMemoryJson;
        var progress = summarizeProgress(goal);
        var prompt = "旧记忆：\n" + prior + "\n\n本次对话：\n" + transcript + "\n\n任务系统新增进展：\n" + progress;
        try {
            var json = llm.completeJson(distillationSystemPrompt(), prompt).orElse(null);
            if (json != null) {
                var asString = json.toString();
                if (asString.length() <= 4000) {
                    goal.distilledMemoryJson = asString;
                } else {
                    // Issue #7: don't silently swallow a >4KB distill. Surface it through
                    // SessionView so the UI can tell the user their long conversation was
                    // too dense to summarise; the prior memory is intact, but this session's
                    // adjustments didn't make it into long-term memory.
                    log.warn("Distillation output {} chars exceeds 4000; keeping prior memory to avoid JSON corruption", asString.length());
                    session.distillationWarning = "本次对话内容过密，长期记忆未能完整更新（"
                            + asString.length() + " 字超出 4000 字上限），上一版本记忆已保留。"
                            + "如关键信息需要保留，建议直接在目标详情中手动备注。";
                }
            }
        } catch (Exception ex) {
            log.warn("Distillation failed: {}", ex.getMessage());
        }
        goal.sessionCount += 1;
        goal.lastSessionAt = LocalDateTime.now();
        goal.updatedAt = LocalDateTime.now();
        goals.save(goal);

        // RAG indexing: feed the (possibly updated) goal memory and session summary into the
        // retrieval corpus so future planning sessions can find this experience. Both calls
        // are idempotent and no-ops when RAG is disabled — safe to invoke unconditionally.
        var memoryText = buildGoalMemorySnippet(goal);
        retrieval.indexOrUpdate(session.user, EmbeddingSourceType.GOAL_MEMORY, goal.id, memoryText);
        if (session.runningSummary != null && !session.runningSummary.isBlank()) {
            retrieval.indexOrUpdate(session.user, EmbeddingSourceType.SESSION_SUMMARY,
                    session.id, session.runningSummary);
        }
    }

    /**
     * 【构建目标记忆嵌入文本：为目标的 RAG 索引生成输入文本，前缀加上目标标题以增强主题捕获】
     *
     * <p>English: The text we actually embed for GOAL_MEMORY. We prepend the goal title so the embedding
     * captures the topic even when the distilled JSON is sparse or has just been initialised.</p>
     *
     * @param goal 【目标实体】
     * @return 【用于嵌入的文本，格式为"目标：{title}\n{distilledMemoryJson}"】
     */
    private String buildGoalMemorySnippet(Goal goal) {
        var sb = new StringBuilder();
        sb.append("目标：").append(safe(goal.title));
        if (goal.distilledMemoryJson != null && !goal.distilledMemoryJson.isBlank()) {
            sb.append("\n").append(goal.distilledMemoryJson);
        }
        return sb.toString();
    }

    /**
     * 【渲染完整对话记录：将会话的全部轮次格式化为"用户：/助手："的纯文本，用于蒸馏 prompt】
     *
     * @param session 【当前会话】
     * @return 【格式化的对话记录字符串】
     */
    private String renderTranscript(PlanningSession session) {
        var all = turns.findBySessionOrderByIdxAsc(session);
        var sb = new StringBuilder();
        for (var t : all) {
            sb.append(t.role == TurnRole.USER ? "用户：" : "助手：")
                    .append(safe(t.content)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 【汇总目标进展：从任务数据库查询已完成和进行中/待办的任务，格式化为人类可读的进展文本】
     *
     * <p>已完成任务取最近 10 条（以 lastSessionAt 为分界），进行中/待办任务取前 10 条。
     * 输出格式："已完成: N 项（最近：xxx）\n进行中/待办: N 项（xxx）"。</p>
     *
     * @param goal 【目标实体】
     * @return 【进展摘要文本】
     */
    private String summarizeProgress(Goal goal) {
        if (goal.id == null) return "（暂无）";
        var related = tasks.findByUserAndGoalIdOrderByCreatedAtDesc(goal.user, goal.id);
        if (related.isEmpty()) return "（暂无关联任务）";
        var since = goal.lastSessionAt;
        var completed = related.stream()
                .filter(t -> t.status == TaskStatus.COMPLETED)
                .filter(t -> since == null || (t.completedAt != null && t.completedAt.isAfter(since)))
                .sorted(Comparator.comparing((StudyTask t) -> t.completedAt == null ? LocalDateTime.MIN : t.completedAt).reversed())
                .limit(10)
                .toList();
        var open = related.stream()
                .filter(t -> t.status != TaskStatus.COMPLETED)
                .limit(10)
                .toList();
        var sb = new StringBuilder();
        sb.append("已完成: ").append(completed.size()).append(" 项");
        if (!completed.isEmpty()) {
            sb.append("（最近：");
            for (int i = 0; i < completed.size(); i++) {
                if (i > 0) sb.append("；");
                sb.append(safe(completed.get(i).title));
            }
            sb.append("）");
        }
        sb.append("\n进行中/待办: ").append(open.size()).append(" 项");
        if (!open.isEmpty()) {
            sb.append("（");
            for (int i = 0; i < open.size(); i++) {
                if (i > 0) sb.append("；");
                sb.append(safe(open.get(i).title));
            }
            sb.append("）");
        }
        return sb.toString();
    }

    // ----- helpers ------------------------------------------------------

    /**
     * 【创建并持久化新会话：初始化 PlanningSession 实体并保存到数据库】
     *
     * @param user  【当前登录用户】
     * @param goal  【关联目标】
     * @param kind  【会话类型：NEW_GOAL 或 CHECK_IN】
     * @return 【已持久化的会话实体】
     */
    private PlanningSession openSession(UserAccount user, Goal goal, SessionKind kind) {
        var session = new PlanningSession();
        session.user = user;
        session.goal = goal;
        session.kind = kind;
        session.state = SessionState.DRAFTING;
        return sessions.save(session);
    }

    /**
     * 【追加对话轮次：创建新的 SessionTurn 记录并更新会话的轮次计数和最后活跃时间】
     *
     * @param session 【当前会话】
     * @param role    【发言角色：USER / ASSISTANT / SYSTEM】
     * @param content 【发言内容】
     */
    private void appendTurn(PlanningSession session, TurnRole role, String content) {
        var turn = new SessionTurn();
        turn.session = session;
        turn.idx = session.turnCount;
        turn.role = role;
        turn.content = content;
        turns.save(turn);
        session.turnCount += 1;
        session.lastActivityAt = LocalDateTime.now();
        sessions.save(session);
    }

    /**
     * 【加载并校验会话归属：根据 ID 加载会话并验证属于当前用户】
     *
     * @param user      【当前登录用户】
     * @param sessionId 【会话 ID】
     * @return 【会话实体】
     * @throws NotFoundException  【会话不存在】
     * @throws ForbiddenException 【会话不属于当前用户】
     */
    private PlanningSession loadOwned(UserAccount user, Long sessionId) {
        var session = sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Session not found"));
        if (!Objects.equals(session.user.id, user.id)) throw new ForbiddenException("Session belongs to another user");
        return session;
    }

    /**
     * 【校验会话活跃状态：确保会话和底层目标均处于可交互状态】
     *
     * <p>会话本身不能是 COMMITTED / ABANDONED / CLOSED 状态；
     * 底层目标不能是 ABANDONED / ARCHIVED 状态（防止目标被外部删除后仍可对话）。</p>
     *
     * @param session 【会话实体】
     * @throws BadRequestException 【会话或目标不再活跃】
     */
    private void ensureActive(PlanningSession session) {
        if (session.state == SessionState.COMMITTED
                || session.state == SessionState.ABANDONED
                || session.state == SessionState.CLOSED) {
            throw new BadRequestException("Session is no longer active");
        }
        // The session itself may still be DRAFTING, but if the underlying goal has been
        // archived/abandoned out-of-band (e.g. via goal delete), the conversation should stop.
        var gs = session.goal.status;
        if (gs == GoalStatus.ABANDONED || gs == GoalStatus.ARCHIVED) {
            throw new BadRequestException("Goal is no longer active");
        }
    }

    /**
     * 【生成兜底回复：当 LLM 不可用时返回预设的引导性问题，保持对话不中断】
     *
     * @param session 【当前会话】
     * @return 【根据会话类型返回不同的兜底话术】
     */
    private String fallbackReply(PlanningSession session) {
        if (session.kind == SessionKind.NEW_GOAL) {
            return "（AI 暂不可用）请描述你希望多久达成这个目标，以及每周大约可以投入多少时间？";
        }
        return "（AI 暂不可用）这段时间你完成了哪些进展，遇到了什么阻塞？";
    }

    /**
     * 【构建会话视图 DTO：从会话实体和关联数据组装完整的 SessionView 响应对象】
     *
     * @param session              【会话实体】
     * @param latestAssistantText  【最新的 AI 回复文本（可为 null）】
     * @param dupes                【重复目标候选列表】
     * @return 【会话视图 DTO】
     */
    private SessionDtos.SessionView view(PlanningSession session, String latestAssistantText,
                                         List<SessionDtos.DuplicateCandidate> dupes) {
        var all = turns.findBySessionOrderByIdxAsc(session);
        var turnViews = all.stream()
                .map(t -> new SessionDtos.TurnView(t.id, t.idx, t.role, t.content))
                .toList();
        Object pending = null;
        if (session.pendingPlanJson != null && !session.pendingPlanJson.isBlank()) {
            try { pending = mapper.readTree(session.pendingPlanJson); }
            catch (Exception ignored) { pending = null; }
        }
        // Re-extract a CLARIFY_JSON envelope from the latest assistant turn so the UI can render
        // selectable option cards. A proposed plan takes priority — once we have a plan, the
        // clarify cards are stale, so suppress them while PLAN_PROPOSED.
        Object pendingClarify = null;
        if (session.state != SessionState.PLAN_PROPOSED) {
            var clarify = extractClarifyEnvelope(latestAssistantContent(session));
            if (clarify != null && hasUsableClarify(clarify)) pendingClarify = clarify;
        }
        return new SessionDtos.SessionView(
                session.id,
                session.goal.id,
                session.goal.title,
                session.kind,
                session.state,
                session.turnCount,
                pending,
                pendingClarify,
                turnViews,
                suggestedActions(session),
                dupes,
                session.distillationWarning
        );
    }

    /**
     * 【获取建议操作列表：根据当前会话状态返回前端可展示的操作按钮标识】
     *
     * <p>DRAFTING 状态显示"直接给计划"和"放弃"；PLAN_PROPOSED 状态显示"确认""调整""放弃"。</p>
     *
     * @param session 【当前会话】
     * @return 【建议操作标识列表】
     */
    private List<String> suggestedActions(PlanningSession session) {
        return switch (session.state) {
            case DRAFTING -> List.of("just_give_me_a_plan", "abandon");
            case PLAN_PROPOSED -> List.of("commit", "adjust", "abandon");
            default -> List.of();
        };
    }

    /**
     * 【安全枚举解析：将字符串解析为指定枚举值，解析失败时返回默认值】
     *
     * @param cls      【枚举类】
     * @param value    【待解析的字符串，可为 null】
     * @param fallback 【解析失败时的默认值】
     * @return 【解析后的枚举值或默认值】
     */
    private <E extends Enum<E>> E parseEnum(Class<E> cls, String value, E fallback) {
        if (value == null) return fallback;
        try { return Enum.valueOf(cls, value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { return fallback; }
    }

    /**
     * 【整数范围钳制：将值限制在 [min, max] 区间内】
     *
     * @param v   【原始值】
     * @param min 【下界】
     * @param max 【上界】
     * @return 【钳制后的值】
     */
    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 【空安全字符串转换：null 转为空串，用于 prompt 拼接时避免 NPE】
     *
     * @param value 【原始字符串，可为 null】
     * @return 【非 null 的字符串】
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

}
