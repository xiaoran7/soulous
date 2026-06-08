package com.soulous.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulous.ai.LlmService;
import com.soulous.auth.UserAccount;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.moderation.ModerationService;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 【AI 拆解对话核心服务（Gemini 式重构版）】
 *
 * <p>取代旧的目标中心 {@code PlanningSessionService}：以「分类 → 对话 → 消息」三级结构承载
 * 用户与 AI 学习助手的连续聊天。保留旧版已验证的关键能力——滚动摘要控长、PLAN_JSON 计划信封
 * 抽取与落地为 StudyTask、CLARIFY_JSON 结构化追问、输入/输出内容审核、流式回复——但去掉了
 * 目标、check-in、长期记忆蒸馏、RAG 检索、重复目标检测等复杂分支。</p>
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 【最大对话轮次上限：安全阀，防止极端循环】 */
    private static final int MAX_TURNS = 200;
    /** 【最近窗口：保留原文的最近 N 条消息，更早的折叠进滚动摘要】 */
    private static final int RECENT_TURN_WINDOW = 4;
    /** 【摘要批处理阈值：累计这么多条新的旧消息才触发一次摘要】 */
    private static final int SUMMARY_BATCH = 4;
    /** 【滚动摘要最大字符数】 */
    private static final int SUMMARY_MAX_CHARS = 1500;
    /** 【单条消息最大字符数：放宽以容纳附件文本（每个附件上限 3w 字，可能多个）】 */
    private static final int MAX_MESSAGE_CHARS = 120_000;
    /** 【对话默认标题】 */
    private static final String DEFAULT_TITLE = "新对话";

    private static final String BLOCKED_REPLY = "⚠️ 你的消息未通过内容安全检查，请调整后重试。如有疑问请联系管理员。";
    private static final String OUTPUT_BLOCKED_REPLY = "（AI 回复已被安全系统拦截，请换一种方式提问。）";

    private final ChatCategoryRepository categories;
    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final TaskRepository tasks;
    private final LlmService llm;
    private final ModerationService moderation;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatService(ChatCategoryRepository categories,
                       ChatConversationRepository conversations,
                       ChatMessageRepository messages,
                       TaskRepository tasks,
                       LlmService llm,
                       ModerationService moderation) {
        this.categories = categories;
        this.conversations = conversations;
        this.messages = messages;
        this.tasks = tasks;
        this.llm = llm;
        this.moderation = moderation;
    }

    // ----- categories ---------------------------------------------------

    /** 【侧边栏树：全部分类 + 全部对话摘要（categoryId 为 null 即默认组）】 */
    @Transactional(readOnly = true)
    public ChatDtos.TreeView tree(UserAccount user) {
        var cats = categories.findByUserOrderBySortOrderAscCreatedAtAsc(user).stream()
                .map(c -> new ChatDtos.CategoryView(c.id, c.name))
                .toList();
        var convs = conversations.findByUserOrderByLastActivityAtDesc(user).stream()
                .map(this::conversationSummary)
                .toList();
        return new ChatDtos.TreeView(cats, convs);
    }

    @Transactional
    public ChatDtos.CategoryView createCategory(UserAccount user, String name) {
        var clean = requireName(name);
        var cat = new ChatCategory();
        cat.user = user;
        cat.name = clean;
        cat = categories.save(cat);
        return new ChatDtos.CategoryView(cat.id, cat.name);
    }

    @Transactional
    public ChatDtos.CategoryView renameCategory(UserAccount user, Long id, String name) {
        var cat = loadOwnedCategory(user, id);
        cat.name = requireName(name);
        categories.save(cat);
        return new ChatDtos.CategoryView(cat.id, cat.name);
    }

    /** 【删除分类：其下对话解绑到默认组（category 置空），不删对话】 */
    @Transactional
    public ChatDtos.DeleteResult deleteCategory(UserAccount user, Long id) {
        var cat = loadOwnedCategory(user, id);
        var affected = conversations.findByCategory(cat);
        for (var c : affected) c.category = null;
        conversations.saveAll(affected);
        categories.delete(cat);
        return new ChatDtos.DeleteResult(id);
    }

    // ----- conversations ------------------------------------------------

    @Transactional
    public ChatDtos.ConversationView createConversation(UserAccount user, Long categoryId) {
        var conv = new ChatConversation();
        conv.user = user;
        if (categoryId != null) conv.category = loadOwnedCategory(user, categoryId);
        conv = conversations.save(conv);
        return view(conv);
    }

    @Transactional(readOnly = true)
    public ChatDtos.ConversationView getConversation(UserAccount user, Long id) {
        return view(loadOwned(user, id));
    }

    /** 【更新对话：重命名 和/或 移动分类。clearCategory=true 表示移出到默认组。】 */
    @Transactional
    public ChatDtos.ConversationView updateConversation(UserAccount user, Long id,
                                                        ChatDtos.UpdateConversationRequest body) {
        var conv = loadOwned(user, id);
        if (body.title() != null) {
            var t = body.title().trim();
            if (t.isBlank()) throw new BadRequestException("Title cannot be blank");
            conv.title = t.length() > 200 ? t.substring(0, 200) : t;
        }
        if (Boolean.TRUE.equals(body.clearCategory())) {
            conv.category = null;
        } else if (body.categoryId() != null) {
            conv.category = loadOwnedCategory(user, body.categoryId());
        }
        conversations.save(conv);
        return view(conv);
    }

    @Transactional
    public ChatDtos.DeleteResult deleteConversation(UserAccount user, Long id) {
        var conv = loadOwned(user, id);
        messages.deleteByConversation(conv);
        conversations.delete(conv);
        return new ChatDtos.DeleteResult(id);
    }

    // ----- messaging ----------------------------------------------------

    @Transactional
    public ChatDtos.ConversationView postMessage(UserAccount user, Long id, String content) {
        var conv = loadOwned(user, id);
        var trimmed = validateMessage(content);

        var inputCheck = moderation.moderateInput(user, trimmed, List.of(), conv.id);
        if (inputCheck.blocked()) {
            appendMessage(conv, ChatRole.USER, trimmed);
            appendMessage(conv, ChatRole.ASSISTANT, BLOCKED_REPLY);
            return view(conv);
        }

        // 用户继续发言时作废上一版待确认计划，避免落地与对话脱节的旧计划
        conv.pendingPlanJson = null;
        appendMessage(conv, ChatRole.USER, trimmed);
        maybeAutoTitle(conv, trimmed);
        runAssistantTurn(conv, null);
        return view(conv);
    }

    @Transactional
    public ChatDtos.ConversationView postMessageStream(UserAccount user, Long id, String content,
                                                       Consumer<String> onChunk) {
        var conv = loadOwned(user, id);
        var trimmed = validateMessage(content);

        var inputCheck = moderation.moderateInput(user, trimmed, List.of(), conv.id);
        if (inputCheck.blocked()) {
            appendMessage(conv, ChatRole.USER, trimmed);
            appendMessage(conv, ChatRole.ASSISTANT, BLOCKED_REPLY);
            onChunk.accept(BLOCKED_REPLY);
            return view(conv);
        }

        conv.pendingPlanJson = null;
        appendMessage(conv, ChatRole.USER, trimmed);
        maybeAutoTitle(conv, trimmed);
        runAssistantTurn(conv, onChunk);
        return view(conv);
    }

    // ----- plan editing / commit ---------------------------------------

    @Transactional
    public ChatDtos.ConversationView editPlanTask(UserAccount user, Long id, int index,
                                                  ChatDtos.EditPlanTaskRequest patch) {
        var conv = loadOwned(user, id);
        var arr = pendingTasksArrayForEdit(conv);
        if (index < 0 || index >= arr.size()) throw new BadRequestException("Task index out of range");
        var task = (ObjectNode) arr.get(index);
        if (patch.title() != null) {
            var t = patch.title().trim();
            if (t.isBlank()) throw new BadRequestException("Title cannot be blank");
            task.put("title", t.length() > 128 ? t.substring(0, 128) : t);
        }
        if (patch.description() != null) task.put("description", patch.description());
        if (patch.estimatedMinutes() != null) task.put("estimatedMinutes", clampInt(patch.estimatedMinutes(), 5, 240));
        if (patch.difficulty() != null) task.put("difficulty", parseEnum(Difficulty.class, patch.difficulty(), Difficulty.NORMAL).name());
        if (patch.taskType() != null) task.put("taskType", parseEnum(TaskType.class, patch.taskType(), TaskType.STUDY).name());
        if (patch.baseExp() != null) task.put("baseExp", clampInt(patch.baseExp(), 5, 100));
        savePendingPlan(conv, arr);
        return view(conv);
    }

    @Transactional
    public ChatDtos.ConversationView deletePlanTask(UserAccount user, Long id, int index) {
        var conv = loadOwned(user, id);
        var arr = pendingTasksArrayForEdit(conv);
        if (index < 0 || index >= arr.size()) throw new BadRequestException("Task index out of range");
        if (arr.size() <= 1) throw new BadRequestException("Plan must keep at least one task");
        arr.remove(index);
        savePendingPlan(conv, arr);
        return view(conv);
    }

    /** 【确认计划：落地为 StudyTask（goalId 为空，不挂目标），清空待确认计划】 */
    @Transactional
    public ChatDtos.ConversationView commitPlan(UserAccount user, Long id) {
        var conv = loadOwned(user, id);
        if (conv.pendingPlanJson == null || conv.pendingPlanJson.isBlank()) {
            throw new BadRequestException("No plan to commit");
        }
        materializePlan(conv);
        conv.pendingPlanJson = null;
        conversations.save(conv);
        return view(conv);
    }

    // ----- turn runner --------------------------------------------------

    /**
     * 【AI 回复执行器：onChunk 非空时走流式，逐 token 推送；为空时一次性生成。
     * 复用旧版的兜底/输出审核/PLAN_JSON 抽取/持久化逻辑。】
     */
    private void runAssistantTurn(ChatConversation conv, Consumer<String> onChunk) {
        if (conv.turnCount >= MAX_TURNS) {
            var forced = "（本对话已很长，建议新开一个对话继续。）";
            appendMessage(conv, ChatRole.ASSISTANT, forced);
            if (onChunk != null) onChunk.accept(forced);
            return;
        }

        var system = systemPrompt();
        maybeUpdateRunningSummary(conv);
        var userPrompt = buildLayeredUserPrompt(conv);

        var captured = new StringBuilder();
        String reply;
        try {
            if (onChunk != null && llm.supportsStreaming()) {
                Consumer<String> tracking = chunk -> {
                    if (chunk == null) return;
                    captured.append(chunk);
                    onChunk.accept(chunk);
                };
                reply = llm.stream(system, userPrompt, tracking);
                if (reply == null || reply.isBlank()) {
                    reply = fallbackReply();
                    onChunk.accept(reply);
                }
            } else {
                reply = llm.complete(system, userPrompt)
                        .filter(s -> s != null && !s.isBlank())
                        .orElseGet(this::fallbackReply);
                if (onChunk != null) onChunk.accept(reply);
            }
        } catch (Exception ex) {
            log.warn("LLM call failed for conversation {}: {}", conv.id, ex.getMessage());
            if (onChunk != null && captured.length() > 0) {
                var marker = "\n\n（与 AI 的连接中断，以上为部分回复。可继续发送消息让 AI 接着说。）";
                onChunk.accept(marker);
                reply = captured + marker;
            } else {
                reply = fallbackReply();
                if (onChunk != null) onChunk.accept(reply);
            }
        }

        var history = messages.findByConversationOrderByIdxAsc(conv);
        var lastUserContent = history.stream()
                .filter(m -> m.role == ChatRole.USER)
                .reduce((a, b) -> b)
                .map(m -> m.content)
                .orElse("");
        var outputCheck = moderation.moderateOutput(conv.user, reply, lastUserContent, List.of(), conv.id);
        if (outputCheck.blocked()) {
            log.warn("Output blocked for conversation {}: {}", conv.id, outputCheck.reason());
            reply = OUTPUT_BLOCKED_REPLY;
        }

        var plan = extractPlanEnvelope(reply);
        if (plan != null && hasUsablePlanTasks(plan)) {
            conv.pendingPlanJson = plan.toString();
        } else {
            if (plan != null) {
                log.warn("Conversation {} got a PLAN_JSON envelope with no usable tasks; ignoring.", conv.id);
            }
            // Harness repair: the model sometimes claims "已生成计划草案" in prose but forgets the
            // machine-readable <PLAN_JSON> envelope, so no plan card ever renders (user pain point).
            // When the reply reads like a plan claim yet carries neither a usable PLAN nor a CLARIFY
            // envelope, fire one focused follow-up that converts the just-described plan into a strict
            // envelope. Recovers the card without making the user re-ask.
            if (extractClarifyEnvelope(reply) == null && looksLikePlanClaim(reply)) {
                var repaired = repairPlanEnvelope(conv, reply);
                if (repaired != null && hasUsablePlanTasks(repaired)) {
                    conv.pendingPlanJson = repaired.toString();
                    log.info("Conversation {}: recovered a missing PLAN_JSON envelope via repair pass.", conv.id);
                }
            }
        }
        appendMessage(conv, ChatRole.ASSISTANT, reply);
    }

    /** 【分层用户 prompt（精简版）：[SUMMARY] + [RECENT] + [CURRENT]】 */
    private String buildLayeredUserPrompt(ChatConversation conv) {
        var sb = new StringBuilder();
        sb.append("[SUMMARY]\n");
        if (conv.runningSummary == null || conv.runningSummary.isBlank()) sb.append("（暂无早期摘要）");
        else sb.append(conv.runningSummary);
        sb.append("\n\n");

        var recent = loadRecentWindow(conv);
        sb.append("[RECENT]\n");
        if (recent.isEmpty()) {
            sb.append("（无）");
        } else {
            var last = recent.get(recent.size() - 1);
            var context = recent.subList(0, recent.size() - 1);
            if (context.isEmpty()) sb.append("（无）");
            else for (var m : context) {
                sb.append(m.role == ChatRole.USER ? "用户：" : "助手：").append(safe(m.content)).append('\n');
            }
            sb.append("\n[CURRENT] (").append(last.role == ChatRole.USER ? "用户" : "助手").append(")\n");
            sb.append(safe(last.content));
        }
        return sb.toString();
    }

    private List<ChatMessage> loadRecentWindow(ChatConversation conv) {
        var recent = messages.findTop12ByConversationOrderByIdxDesc(conv);
        Collections.reverse(recent);
        var filtered = new ArrayList<ChatMessage>(recent.size());
        for (var m : recent) if (m.idx >= conv.summarizedUpToIdx) filtered.add(m);
        int from = Math.max(0, filtered.size() - RECENT_TURN_WINDOW);
        return new ArrayList<>(filtered.subList(from, filtered.size()));
    }

    // ----- rolling summary ---------------------------------------------

    private void maybeUpdateRunningSummary(ChatConversation conv) {
        if (!llm.isAvailable()) return;
        var all = messages.findByConversationOrderByIdxAsc(conv);
        if (all.size() <= RECENT_TURN_WINDOW) return;
        var cutoffExclusive = all.size() - RECENT_TURN_WINDOW;
        var pending = new ArrayList<ChatMessage>();
        for (var m : all) {
            if (m.idx < conv.summarizedUpToIdx) continue;
            if (recentIndex(all, m.idx) < cutoffExclusive) pending.add(m);
        }
        if (pending.size() < SUMMARY_BATCH) return;

        var newest = pending.get(pending.size() - 1);
        var transcript = new StringBuilder();
        for (var m : pending) {
            transcript.append(m.role == ChatRole.USER ? "用户：" : "助手：").append(safe(m.content)).append('\n');
        }
        var prior = conv.runningSummary == null ? "（无）" : conv.runningSummary;
        var userPrompt = "旧摘要：\n" + prior + "\n\n新增对话片段：\n" + transcript;
        try {
            var fresh = llm.complete(summarySystemPrompt(), userPrompt).orElse(null);
            if (fresh == null || fresh.isBlank()) return;
            if (fresh.length() > SUMMARY_MAX_CHARS) fresh = fresh.substring(0, SUMMARY_MAX_CHARS);
            conv.runningSummary = fresh;
            conv.summarizedUpToIdx = newest.idx + 1;
            conversations.save(conv);
        } catch (Exception ex) {
            log.warn("Rolling-summary update failed for conversation {}: {}", conv.id, ex.getMessage());
        }
    }

    private int recentIndex(List<ChatMessage> all, int idx) {
        for (int i = 0; i < all.size(); i++) if (all.get(i).idx == idx) return i;
        return -1;
    }

    // ----- prompts ------------------------------------------------------

    private String systemPrompt() {
        return """
                你是用户的 AI 学习助手，帮助用户解答学习问题、梳理思路，并在合适时把目标拆解成可执行的学习任务。

                输入结构（用户消息里可能包含以下段落，请分别理解）：
                  [SUMMARY]  本次对话早期内容的压缩摘要（可能为空）
                  [RECENT]   最近若干轮原文对话
                  [CURRENT]  用户当前这条发言（最需要你直接回应）
                用户消息里可能还带有 <ATTACHMENT name="...">...</ATTACHMENT> 或「【附件：...】」包裹的文件内容
                （用户上传的 md/pdf/txt 提取文本），请把它当作用户提供的资料一起理解。

                行为规则：
                1. 平时用简洁、有条理的中文正常对话答疑，不要无故强行拆任务。
                2. 当用户明确想「把它拆成任务 / 给我一个学习计划 / 制定计划」，或当前诉求确实适合落地为一组可执行学习任务时，在回复末尾输出 <PLAN_JSON> 信封。
                3. 计划必须包含一个 category（这组任务的「大类」归类名，必填，≤12 字，如「数据结构」「考研数学」「英语口语」）和 3–7 个任务；每个任务字段：title（≤40字）、description、estimatedMinutes（15–90）、difficulty（EASY/NORMAL/HARD）、taskType（STUDY/CODING/NOTE/MEMORY/REVIEW/SIMPLE）。由易到难、循序渐进。你必须主动起好这个大类名，让用户落地后任务自动归类，绝不要把分类留空让用户自己补。
                4. 信息不足以拆解、且关键空白会实质影响任务编排时，可用 <CLARIFY_JSON> 信封提问（≤2 题、每题 2–4 个可直接点选的完整选项），不要用纯文字问。一条回复里 CLARIFY_JSON 与 PLAN_JSON 二选一。
                5. 你"问问题"必须通过 <CLARIFY_JSON>...</CLARIFY_JSON>、"给计划"必须通过 <PLAN_JSON>...</PLAN_JSON>，且必须真的输出对应标签包裹的合法 JSON。绝对禁止只在文字里声称「已生成计划草案 / 计划如下 / 已为你拆好 N 个任务」却不输出对应的 <PLAN_JSON> 信封——只要你打算让用户看到一份计划，就必须在同一条回复里给出完整的 <PLAN_JSON>。标签外可有简短自然语言说明，但 JSON 必须完整。

                <CLARIFY_JSON> 格式（每题 2–4 个 options，单选 multiSelect:false，多选 true）：
                <CLARIFY_JSON>
                {"questions":[{"id":"tool","question":"你打算用什么工具或语言？","multiSelect":false,"options":[{"label":"Python","hint":"适合数据/AI"},{"label":"JavaScript"}]}]}
                </CLARIFY_JSON>

                <PLAN_JSON> 格式（category 必填，是这组任务的大类归类名）：
                <PLAN_JSON>
                {"category":"数据结构","tasks":[{"title":"...","description":"...","estimatedMinutes":30,"difficulty":"NORMAL","taskType":"STUDY"}]}
                </PLAN_JSON>
                """;
    }

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

    // ----- plan envelope + materialization -----------------------------

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

    private JsonNode extractPlanEnvelope(String reply) {
        if (reply == null) return null;
        var open = reply.indexOf("<PLAN_JSON>");
        var close = reply.indexOf("</PLAN_JSON>");
        if (open < 0 || close <= open) return null;
        var inner = reply.substring(open + "<PLAN_JSON>".length(), close).trim();
        try { return mapper.readTree(inner); }
        catch (Exception ex) { log.warn("Plan envelope parse failed: {}", ex.getMessage()); return null; }
    }

    /**
     * 【启发式判断：这段回复是不是「声称给了计划但没给信封」】。
     * 仅当回复既提到计划/任务，又用了「草案 / 已生成 / 计划如下 / ↓ / 为你定制 / 拆成…个任务」等
     * 表示「我已经把计划摆出来了」的措辞时才返回 true，用来触发补救式的信封重抽，尽量不误伤普通答疑。
     */
    private static boolean looksLikePlanClaim(String reply) {
        if (reply == null || reply.isBlank()) return false;
        boolean mentionsPlan = reply.contains("计划") || reply.contains("任务")
                || reply.contains("plan") || reply.contains("PLAN");
        if (!mentionsPlan) return false;
        return reply.contains("草案") || reply.contains("已生成") || reply.contains("如下")
                || reply.contains("↓") || reply.contains("PLAN_JSON") || reply.contains("为你定制")
                || reply.contains("已为你") || reply.contains("制定了") || reply.contains("帮你拆")
                || reply.contains("拆成") || reply.contains("拆解为") || reply.contains("个任务");
    }

    /**
     * 【补救式信封重抽：把模型刚才用自然语言描述的计划，二次调用 LLM 转成严格的 PLAN_JSON】。
     * 用带一次自纠重试的 JSON 校验调用（要求至少一个有效任务），失败返回 null，调用方据此放弃补救。
     * 走可被测试桩覆写的 {@link LlmService#completeJsonValidated(String, String, java.util.function.Predicate)}，
     * 让脚本化 LLM 能在测试里直接喂回一份修好的计划 JSON。
     */
    private JsonNode repairPlanEnvelope(ChatConversation conv, String prose) {
        if (!llm.isAvailable()) return null;
        var user = "你刚才对用户说的下面这段话里描述了一份学习计划，但你忘了输出机器可读的计划数据，"
                + "导致用户看不到可确认的计划卡片。请把你刚才描述的这份计划严格转换成约定的 JSON。\n\n"
                + "你刚才的话：\n" + safe(prose);
        try {
            return llm.completeJsonValidated(planRepairSystemPrompt(), user, ChatService::hasUsablePlanTasks)
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Plan-envelope repair failed for conversation {}: {}", conv.id, ex.getMessage());
            return null;
        }
    }

    private String planRepairSystemPrompt() {
        return """
                你是一个把自然语言学习计划转写成结构化 JSON 的助手。只输出一个 JSON 对象，禁止任何解释、Markdown 围栏或多余文字。
                Schema：
                {"category":"这组任务的大类归类名（必填，≤12字，如「数据结构」「考研数学」）","tasks":[{"title":"≤40字","description":"...","estimatedMinutes":30,"difficulty":"EASY|NORMAL|HARD","taskType":"STUDY|CODING|NOTE|MEMORY|REVIEW|SIMPLE"}]}
                要求：
                - 忠实还原用户那段话里描述的任务，3–7 个，不要凭空新增或删减成完全不同的计划。
                - category 必须自己起好，绝不能留空。
                - 字段缺失时按合理默认补全（estimatedMinutes 取 15–90，difficulty 默认 NORMAL，taskType 默认 STUDY）。
                """;
    }

    private JsonNode extractClarifyEnvelope(String reply) {
        if (reply == null) return null;
        var open = reply.indexOf("<CLARIFY_JSON>");
        var close = reply.indexOf("</CLARIFY_JSON>");
        if (open < 0 || close <= open) return null;
        var inner = reply.substring(open + "<CLARIFY_JSON>".length(), close).trim();
        try { return mapper.readTree(inner); }
        catch (Exception ex) { log.warn("Clarify envelope parse failed: {}", ex.getMessage()); return null; }
    }

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

    private ArrayNode pendingTasksArrayForEdit(ChatConversation conv) {
        if (conv.pendingPlanJson == null || conv.pendingPlanJson.isBlank()) {
            throw new BadRequestException("No pending plan to edit");
        }
        JsonNode plan;
        try { plan = mapper.readTree(conv.pendingPlanJson); }
        catch (Exception ex) { throw new BadRequestException("Pending plan is malformed"); }
        var tasksNode = plan.path("tasks");
        if (!tasksNode.isArray()) throw new BadRequestException("Pending plan has no tasks");
        return (ArrayNode) tasksNode;
    }

    private void savePendingPlan(ChatConversation conv, ArrayNode tasksArr) {
        try {
            var root = (ObjectNode) mapper.readTree(conv.pendingPlanJson);
            root.set("tasks", tasksArr);
            conv.pendingPlanJson = mapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new BadRequestException("Failed to update plan");
        }
        conversations.save(conv);
    }

    private List<StudyTask> materializePlan(ChatConversation conv) {
        JsonNode plan;
        try { plan = mapper.readTree(conv.pendingPlanJson); }
        catch (Exception ex) { throw new BadRequestException("Pending plan is malformed"); }
        var tasksJson = plan.path("tasks");
        if (!tasksJson.isArray() || tasksJson.isEmpty()) throw new BadRequestException("Plan has no tasks to commit");

        // 大分类：优先用对话所属分类；对话未分类时，退回到 AI 在计划信封里起好的 category 大类，
        // 这样「新对话」里直接拆出来的任务也自带归类，用户不必再手动建分类、逐个改任务。
        var planCategory = plan.path("category").asText("").trim();
        String category = conv.category != null ? conv.category.name
                : (planCategory.isBlank() ? null
                        : (planCategory.length() > 60 ? planCategory.substring(0, 60) : planCategory));

        var created = new ArrayList<StudyTask>();
        for (var node : tasksJson) {
            var title = node.path("title").asText("").trim();
            if (title.isBlank()) continue;
            var task = new StudyTask();
            task.user = conv.user;
            task.title = title.length() > 128 ? title.substring(0, 128) : title;
            task.description = node.path("description").asText("");
            task.taskType = parseEnum(TaskType.class, node.path("taskType").asText("STUDY"), TaskType.STUDY);
            task.difficulty = parseEnum(Difficulty.class, node.path("difficulty").asText("NORMAL"), Difficulty.NORMAL);
            task.estimatedMinutes = clampInt(node.path("estimatedMinutes").asInt(30), 5, 240);
            task.baseExp = clampInt(node.path("baseExp").asInt(20), 5, 100);
            task.status = TaskStatus.TODO;
            task.goalId = null; // 聊天落地的任务不挂目标
            // 大分类：对话分类优先，否则用 AI 计划信封里给出的 category，
            // 让「任务」页能按 AI 拆解的大类聚合这些任务。
            task.category = category;
            created.add(task);
            if (created.size() >= 8) break;
        }
        if (created.isEmpty()) throw new BadRequestException("Plan has no valid tasks to commit");
        tasks.saveAll(created);
        return created;
    }

    // ----- helpers ------------------------------------------------------

    private String latestAssistantContent(ChatConversation conv) {
        var all = messages.findByConversationOrderByIdxAsc(conv);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).role == ChatRole.ASSISTANT) return all.get(i).content;
        }
        return null;
    }

    private void appendMessage(ChatConversation conv, ChatRole role, String content) {
        var msg = new ChatMessage();
        msg.conversation = conv;
        msg.idx = conv.turnCount;
        msg.role = role;
        msg.content = content;
        messages.save(msg);
        conv.turnCount += 1;
        conv.lastActivityAt = LocalDateTime.now();
        conversations.save(conv);
    }

    /** 【首条用户消息后自动命名：去掉附件信封，取前 30 字】 */
    private void maybeAutoTitle(ChatConversation conv, String content) {
        if (conv.title != null && !DEFAULT_TITLE.equals(conv.title)) return;
        var stripped = content
                .replaceAll("(?s)<ATTACHMENT[^>]*>.*?</ATTACHMENT>", " ")
                .replaceAll("【附件：[^】]*】", " ")
                .trim();
        if (stripped.isBlank()) return;
        var firstLine = stripped.split("\\R", 2)[0].trim();
        if (firstLine.isBlank()) return;
        conv.title = firstLine.length() > 30 ? firstLine.substring(0, 30) : firstLine;
        conversations.save(conv);
    }

    private String validateMessage(String content) {
        if (content == null || content.isBlank()) throw new BadRequestException("Message cannot be empty");
        var trimmed = content.trim();
        if (trimmed.length() > MAX_MESSAGE_CHARS) {
            throw new BadRequestException("Message too long (max " + MAX_MESSAGE_CHARS + " chars)");
        }
        return trimmed;
    }

    private ChatConversation loadOwned(UserAccount user, Long id) {
        var conv = conversations.findById(id).orElseThrow(() -> new NotFoundException("Conversation not found"));
        if (!Objects.equals(conv.user.id, user.id)) throw new ForbiddenException("Conversation belongs to another user");
        return conv;
    }

    private ChatCategory loadOwnedCategory(UserAccount user, Long id) {
        var cat = categories.findById(id).orElseThrow(() -> new NotFoundException("Category not found"));
        if (!Objects.equals(cat.user.id, user.id)) throw new ForbiddenException("Category belongs to another user");
        return cat;
    }

    private String requireName(String name) {
        var clean = name == null ? "" : name.trim();
        if (clean.isBlank()) throw new BadRequestException("Name cannot be empty");
        return clean.length() > 60 ? clean.substring(0, 60) : clean;
    }

    private String fallbackReply() {
        return "（AI 暂不可用）请稍后重试，或换一种方式描述你的问题。";
    }

    private ChatDtos.ConversationSummary conversationSummary(ChatConversation conv) {
        return new ChatDtos.ConversationSummary(
                conv.id, conv.title, conv.category == null ? null : conv.category.id,
                conv.lastActivityAt, conv.turnCount);
    }

    private ChatDtos.ConversationView view(ChatConversation conv) {
        var all = messages.findByConversationOrderByIdxAsc(conv);
        var msgViews = all.stream()
                .map(m -> new ChatDtos.MessageView(m.id, m.idx, m.role, m.content))
                .toList();
        Object pendingPlan = null;
        boolean planPending = conv.pendingPlanJson != null && !conv.pendingPlanJson.isBlank();
        if (planPending) {
            try { pendingPlan = mapper.readTree(conv.pendingPlanJson); }
            catch (Exception ignored) { pendingPlan = null; planPending = false; }
        }
        Object pendingClarify = null;
        if (!planPending) {
            var clarify = extractClarifyEnvelope(latestAssistantContent(conv));
            if (clarify != null && hasUsableClarify(clarify)) pendingClarify = clarify;
        }
        return new ChatDtos.ConversationView(
                conv.id, conv.title, conv.category == null ? null : conv.category.id,
                msgViews, pendingPlan, pendingClarify,
                planPending ? List.of("commit", "adjust") : List.of());
    }

    private <E extends Enum<E>> E parseEnum(Class<E> cls, String value, E fallback) {
        if (value == null) return fallback;
        try { return Enum.valueOf(cls, value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { return fallback; }
    }

    private int clampInt(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private String safe(String value) { return value == null ? "" : value; }
}
