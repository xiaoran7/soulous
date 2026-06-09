package com.soulous.review;

import com.soulous.ai.LlmService;
import com.soulous.auth.UserAccount;
import com.soulous.pet.Pet;
import com.soulous.pet.PetRepository;
import com.soulous.pet.PetStatus;
import com.soulous.pet.ExpLogRepository;
import com.soulous.rag.EmbeddingSourceType;
import com.soulous.rag.RetrievalHit;
import com.soulous.rag.RetrievalService;
import com.soulous.task.StudyRecordRepository;
import com.soulous.task.StudyTask;
import com.soulous.task.SubmissionRepository;
import com.soulous.task.SubmissionStatus;
import com.soulous.task.TaskRepository;
import com.soulous.task.TaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 【每日学习复盘服务】
 * 生成用户每日学习复盘报告，包括学习亮点、风险提示和明日建议。
 * 支持同步生成和流式生成两种模式，并可选地通过 RAG 检索历史记忆增强复盘质量。
 * 复盘结果同时会作为 DAILY_REVIEW 类型索引到 RAG 系统中，供未来检索使用。
 */
@Service
public class DailyReviewService {
    /** 【任务仓库】查询用户的学习任务列表。 */
    private final TaskRepository tasks;

    /** 【提交仓库】查询用户的凭证提交记录。 */
    private final SubmissionRepository submissions;

    /** 【经验日志仓库】查询用户今日获得的经验值。 */
    private final ExpLogRepository expLogs;

    /** 【学习记录仓库】查询用户今日的学习时长记录。 */
    private final StudyRecordRepository records;

    /** 【宠物仓库】查询用户的宠物信息。 */
    private final PetRepository pets;

    /** 【LLM 服务】用于生成增强版复盘内容。 */
    private final LlmService llm;

    /**
     * 【RAG 检索服务】可空，用于检索历史学习记忆增强复盘上下文。
     * Nullable so tests can construct without the full RAG stack.
     */
    private final RetrievalService retrieval;

    /**
     * 【主构造器】注入所有依赖，包含 RAG 检索服务。
     *
     * @param tasks       任务仓库
     * @param submissions 提交仓库
     * @param expLogs     经验日志仓库
     * @param records     学习记录仓库
     * @param pets        宠物仓库
     * @param llm         LLM 服务
     * @param retrieval   RAG 检索服务（可空）
     */
    @Autowired
    DailyReviewService(TaskRepository tasks, SubmissionRepository submissions, ExpLogRepository expLogs,
                       StudyRecordRepository records, PetRepository pets, LlmService llm,
                       RetrievalService retrieval) {
        this.tasks = tasks;
        this.submissions = submissions;
        this.expLogs = expLogs;
        this.records = records;
        this.pets = pets;
        this.llm = llm;
        this.retrieval = retrieval;
    }

    /**
     * 【测试专用构造器】不含 RAG 检索服务的简化版本，便于单元测试。
     */
    DailyReviewService(TaskRepository tasks, SubmissionRepository submissions, ExpLogRepository expLogs,
                       StudyRecordRepository records, PetRepository pets, LlmService llm) {
        this(tasks, submissions, expLogs, records, pets, llm, null);
    }

    /**
     * 【同步生成每日复盘】
     * 收集用户今日的学习数据（完成任务、提交凭证、学习时长、经验值等），
     * 生成包含标题、摘要、亮点、风险和明日建议的完整复盘报告。
     * 优先使用 LLM 增强版本，LLM 不可用时降级为基于规则的默认版本。
     * 复盘结果会自动索引到 RAG 系统供未来检索。
     *
     * @param user 当前用户
     * @return 完整的复盘数据 Map，包含 date / title / summary / highlights / risks / tomorrowSuggestions / petMessage / metrics
     */
    public Map<String, Object> generate(UserAccount user) {
        var today = LocalDate.now().atStartOfDay();
        var taskList = tasks.findByUserOrderByCreatedAtDesc(user);
        var submissionList = submissions.findByUserOrderByCreatedAtDesc(user).stream()
                .filter(submission -> submission.createdAt != null && submission.createdAt.isAfter(today))
                .toList();
        var todayLogs = expLogs.findByUserAndCreatedAtAfter(user, today);
        var todayRecords = records.findByUserAndCreatedAtAfter(user, today);
        var completed = taskList.stream().filter(task -> task.status == TaskStatus.COMPLETED && task.completedAt != null && task.completedAt.isAfter(today)).toList();
        var rejected = taskList.stream().filter(task -> task.status == TaskStatus.AI_REJECTED || task.status == TaskStatus.NEED_MORE || task.status == TaskStatus.MANUAL_REJECTED).toList();
        var minutes = todayRecords.stream().mapToInt(record -> record.studyMinutes == null ? 0 : record.studyMinutes).sum();
        var exp = todayLogs.stream().mapToInt(log -> log.expAmount == null ? 0 : log.expAmount).sum();
        var pet = pets.findByUserAndActiveTrue(user).orElse(null);

        // 【构建学习亮点】
        var highlights = new ArrayList<String>();
        if (!completed.isEmpty()) {
            highlights.add("今天完成了 " + completed.size() + " 个已审核通过的学习任务。");
            highlights.add("代表任务：" + completed.getFirst().title);
        }
        if (minutes > 0) {
            highlights.add("记录了 " + minutes + " 分钟学习时间。");
        }
        if (exp > 0) {
            highlights.add("宠物获得 " + exp + " 点经验。");
        }
        if (highlights.isEmpty()) {
            highlights.add("今天还没有形成有效学习记录，可以从一个 15 分钟小任务开始。");
        }

        // 【构建风险提示】
        var risks = new ArrayList<String>();
        if (!rejected.isEmpty()) {
            risks.add("有 " + rejected.size() + " 个任务需要补充凭证或重新提交。");
        }
        if (submissionList.stream().anyMatch(submission -> submission.status == SubmissionStatus.AI_REJECTED)) {
            risks.add("存在被 AI 打回的提交，建议补充具体知识点、截图或代码片段。");
        }
        if (minutes == 0) {
            risks.add("今日学习时长仍为 0，统计趋势还没有形成。");
        }
        if (risks.isEmpty()) {
            risks.add("今天节奏稳定，继续保持具体、可验证的学习凭证。");
        }

        // 【构建明日建议】
        var tomorrow = new ArrayList<String>();
        if (!rejected.isEmpty()) {
            tomorrow.add("优先处理被打回或需要补充的任务：" + rejected.getFirst().title);
        }
        taskList.stream()
                .filter(task -> task.status == TaskStatus.TODO || task.status == TaskStatus.DOING)
                .findFirst()
                .ifPresentOrElse(
                        task -> tomorrow.add("继续推进未完成任务：" + task.title),
                        () -> tomorrow.add("创建一个 30 分钟以内的新学习任务，保持连续性。")
                );
        tomorrow.add("提交凭证时至少写清：学了什么、练了什么、还有什么问题。");

        // 【构建今日指标】
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("completedTasks", completed.size());
        metrics.put("submissions", submissionList.size());
        metrics.put("studyMinutes", minutes);
        metrics.put("earnedExp", exp);
        metrics.put("petLevel", pet == null ? 1 : pet.level);
        metrics.put("petStatus", pet == null ? PetStatus.NORMAL : pet.status);

        var defaultTitle = completed.isEmpty() ? "今天适合从小任务重新启动" : "今天的学习闭环已经形成";
        var defaultSummary = buildSummary(completed.size(), submissionList.size(), minutes, exp);
        var defaultPetMessage = petMessage(pet, exp, rejected.size());

        // 【RAG 检索 + LLM 增强】
        var ragBlock = buildRetrievedBlock(user, completed, rejected, taskList);
        var enhanced = tryLlmEnhance(completed.size(), submissionList.size(), minutes, exp,
                rejected.size(), taskList, completed, rejected, pet, ragBlock);

        // 【组装最终响应体，LLM 增强内容优先，降级为规则默认值】
        var body = new LinkedHashMap<String, Object>();
        body.put("date", LocalDate.now().toString());
        body.put("title", enhanced.getOrDefault("title", defaultTitle));
        body.put("summary", enhanced.getOrDefault("summary", defaultSummary));
        @SuppressWarnings("unchecked")
        var llmHighlights = (List<String>) enhanced.get("highlights");
        @SuppressWarnings("unchecked")
        var llmRisks = (List<String>) enhanced.get("risks");
        @SuppressWarnings("unchecked")
        var llmTomorrow = (List<String>) enhanced.get("tomorrowSuggestions");
        body.put("highlights", llmHighlights == null || llmHighlights.isEmpty() ? highlights : llmHighlights);
        body.put("risks", llmRisks == null || llmRisks.isEmpty() ? risks : llmRisks);
        body.put("tomorrowSuggestions", llmTomorrow == null || llmTomorrow.isEmpty() ? tomorrow : llmTomorrow);
        body.put("petMessage", enhanced.getOrDefault("petMessage", defaultPetMessage));
        body.put("metrics", metrics);
        indexTodayReview(user, body);
        return body;
    }

    /**
     * 【构建 RAG 检索上下文块】
     * 使用今日完成的任务、被打回的任务和待办任务标题作为查询文本，
     * 检索相关的历史学习记忆，为 LLM 提供跨天上下文。
     * RAG 不可用或无命中时返回 null。
     *
     * @param user           当前用户
     * @param completedToday 今日完成的任务列表
     * @param rejectedTasks  被打回的任务列表
     * @param allTasks       所有任务列表
     * @return 格式化的检索上下文文本，或 null
     *
     * <p>English: Builds a [RETRIEVED] block to give the LLM cross-day context. Null when RAG unavailable / empty.</p>
     */
    private String buildRetrievedBlock(UserAccount user, List<StudyTask> completedToday,
                                       List<StudyTask> rejectedTasks, List<StudyTask> allTasks) {
        if (retrieval == null || !retrieval.isEnabled() || user == null) return null;
        var qs = new StringBuilder();
        if (!completedToday.isEmpty()) qs.append(completedToday.get(0).title).append(' ');
        if (!rejectedTasks.isEmpty()) qs.append(rejectedTasks.get(0).title).append(' ');
        for (var t : allTasks) {
            if (t.status == TaskStatus.TODO || t.status == TaskStatus.DOING) {
                qs.append(t.title).append(' ');
                break;
            }
        }
        var query = qs.toString().trim();
        if (query.isEmpty()) return null;

        List<RetrievalHit> hits;
        try {
            hits = retrieval.retrieve(user, query);
        } catch (Exception ex) {
            return null;
        }
        if (hits == null || hits.isEmpty()) return null;

        var out = new StringBuilder("\n[过往学习记忆，仅作参考]\n");
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            if (i > 0) out.append("\n---\n");
            out.append("[").append(i + 1).append("] ").append(hitLabel(h.sourceType()))
                    .append(" (相似度 ").append(String.format(Locale.ROOT, "%.2f", h.similarity())).append(")\n");
            out.append(truncate(h.content() == null ? "" : h.content(), 350));
        }
        out.append("\n（如今日内容与历史呼应，请在 summary/highlights 中点出连续性；否则忽略。）\n");
        return out.toString();
    }

    /**
     * 【索引今日复盘到 RAG】
     * 将今日复盘的标题和摘要作为 DAILY_REVIEW 类型索引，
     * 供未来的复盘检索历史上下文使用。采用尽力而为策略，索引失败不影响复盘响应。
     *
     * @param user 当前用户
     * @param body 复盘数据 Map
     */
    private void indexTodayReview(UserAccount user, Map<String, Object> body) {
        if (retrieval == null) return;
        var summary = String.valueOf(body.getOrDefault("summary", ""));
        var title = String.valueOf(body.getOrDefault("title", ""));
        if (summary.isBlank() && title.isBlank()) return;
        var date = LocalDate.now();
        var snippet = date + " " + title + "：" + summary;
        try {
            retrieval.indexOrUpdate(user, EmbeddingSourceType.DAILY_REVIEW, date.toEpochDay(), snippet);
        } catch (Exception ignored) {
            // best-effort; indexing failure must not affect the review response
            // 【尽力而为；索引失败不能影响复盘响应】
        }
    }

    /**
     * 【检索命中标签映射】将嵌入来源类型转换为中文标签，用于 RAG 检索结果展示。
     *
     * @param t 嵌入来源类型
     * @return 中文标签
     */
    private static String hitLabel(EmbeddingSourceType t) {
        return switch (t) {
            case GOAL_MEMORY -> "过往目标记忆";
            case SESSION_SUMMARY -> "过往拆解对话";
            case COMPLETED_TASK -> "已完成任务";
            case DAILY_REVIEW -> "过往每日复盘";
        };
    }

    /**
     * 【字符串截断】超过最大长度时截断并追加省略号。
     *
     * @param s   输入字符串
     * @param max 最大字符数
     * @return 截断后的字符串
     */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * 【流式生成每日复盘】
     * {@link #generate} 的流式变体：逐 token 推送 LLM 的自然语言叙述，
     * 使 UI 可以像聊天回复一样实时渲染；结构化 JSON 数据在流结束时作为最终结果返回。
     * 当默认 LLM 提供商不支持流式时，降级为同步 {@link #generate}。
     *
     * @param user    当前用户
     * @param onChunk 接收每个 token 片段的回调函数
     * @return 与 {@link #generate} 相同结构的 Map
     *
     * <p>English: Streaming variant of {@link #generate}: emit the LLM's natural-language summary token
     * by token so the UI can render it like a chat reply, while the structured JSON envelope
     * comes back at the end and overrides the rule-based defaults. Falls back to one-shot
     * {@link #generate} when the default provider doesn't support streaming.</p>
     *
     * <p>English: @return the same map structure as {@link #generate} once the stream finishes</p>
     */
    public Map<String, Object> generateStream(com.soulous.auth.UserAccount user,
                                              java.util.function.Consumer<String> onChunk) {
        if (!llm.supportsStreaming()) {
            // Best we can do: produce the full structured review and pretend to stream
            // by emitting the summary text as one chunk. The UI degrades gracefully.
            // 【降级处理：生成完整结构化复盘，将摘要文本作为单个 chunk 推送。UI 可优雅降级。】
            var body = generate(user);
            var summary = String.valueOf(body.getOrDefault("summary", ""));
            if (!summary.isBlank()) onChunk.accept(summary);
            return body;
        }

        var today = LocalDate.now().atStartOfDay();
        var taskList = tasks.findByUserOrderByCreatedAtDesc(user);
        var submissionList = submissions.findByUserOrderByCreatedAtDesc(user).stream()
                .filter(submission -> submission.createdAt != null && submission.createdAt.isAfter(today))
                .toList();
        var todayLogs = expLogs.findByUserAndCreatedAtAfter(user, today);
        var todayRecords = records.findByUserAndCreatedAtAfter(user, today);
        var completed = taskList.stream().filter(t -> t.status == TaskStatus.COMPLETED && t.completedAt != null && t.completedAt.isAfter(today)).toList();
        var rejected = taskList.stream().filter(t -> t.status == TaskStatus.AI_REJECTED || t.status == TaskStatus.NEED_MORE || t.status == TaskStatus.MANUAL_REJECTED).toList();
        var minutes = todayRecords.stream().mapToInt(r -> r.studyMinutes == null ? 0 : r.studyMinutes).sum();
        var exp = todayLogs.stream().mapToInt(l -> l.expAmount == null ? 0 : l.expAmount).sum();
        var pet = pets.findByUserAndActiveTrue(user).orElse(null);
        var ragBlock = buildRetrievedBlock(user, completed, rejected, taskList);

        // System prompt: ask the LLM to narrate THEN emit envelope. Same envelope schema
        // as the JSON-only path so downstream parsing stays unified.
        // 【系统提示词：要求 LLM 先输出自然语言叙述，再输出 JSON 信封。
        // 信封格式与纯 JSON 路径一致，保持下游解析统一。】
        var system = "你是温和、具体的学习教练。请按以下两段格式给出今日复盘：\n"
                + "第一部分：用 80-160 字的自然语言总览，温暖地告诉用户他们今天的状态、亮点与建议（直接面向用户写，不要列表，不要表头）。\n"
                + "第二部分：紧接着输出 <REVIEW_JSON>{...}</REVIEW_JSON>，包含字段：title(短句标题)、summary(2-3 句总览)、highlights(2-4 条亮点)、risks(1-3 条需要留意)、tomorrowSuggestions(2-3 条明日建议)、petMessage(对宠物的话, 1 句)。"
                + "envelope 前后不要有 Markdown 围栏。"
                + (ragBlock == null ? "" : ragBlock);

        var promptSb = new StringBuilder("今日数据：\n");
        promptSb.append("- 完成任务数：").append(completed.size()).append("\n");
        promptSb.append("- 提交凭证数：").append(submissionList.size()).append("\n");
        promptSb.append("- 学习分钟：").append(minutes).append("\n");
        promptSb.append("- 获得经验：").append(exp).append("\n");
        promptSb.append("- 被打回任务数：").append(rejected.size()).append("\n");
        if (!completed.isEmpty()) promptSb.append("- 完成的代表任务：").append(completed.getFirst().title).append("\n");
        if (!rejected.isEmpty()) promptSb.append("- 需要补充的任务：").append(rejected.getFirst().title).append("\n");
        taskList.stream().filter(t -> t.status == TaskStatus.TODO || t.status == TaskStatus.DOING).findFirst()
                .ifPresent(t -> promptSb.append("- 未完成进行中的任务：").append(t.title).append("\n"));
        if (pet != null) promptSb.append("- 宠物：").append(pet.name).append("，Lv.").append(pet.level).append("，状态 ").append(pet.status).append("\n");

        // Stream tokens — but suppress anything inside <REVIEW_JSON>...</REVIEW_JSON> so
        // the user only sees the natural-language part. The emitted-cursor remembers how
        // far into the running buffer we've already sent down to the client.
        // 【流式推送 token——但过滤掉 <REVIEW_JSON>...</REVIEW_JSON> 内的内容，
        // 用户只看到自然语言部分。emitted-cursor 记录已推送到客户端的缓冲区位置。】
        var full = new StringBuilder();
        var emittedUpTo = new int[]{0};
        java.util.function.Consumer<String> narratorOnly = chunk -> {
            full.append(chunk);
            var combined = full.toString();
            var openIdx = combined.indexOf("<REVIEW_JSON>");
            var emitUpTo = openIdx >= 0 ? openIdx : combined.length();
            if (emitUpTo > emittedUpTo[0]) {
                onChunk.accept(combined.substring(emittedUpTo[0], emitUpTo));
                emittedUpTo[0] = emitUpTo;
            }
        };
        String reply;
        try {
            reply = llm.stream(system, promptSb.toString(), narratorOnly);
        } catch (Exception ex) {
            // Stream failed: fall back to non-stream generate path.
            // 【流式失败：降级为同步生成路径】
            return generate(user);
        }
        if (reply == null || reply.isBlank()) {
            return generate(user);
        }

        // Parse envelope from the accumulated reply.
        // 【从累积的完整回复中解析 JSON 信封】
        Map<String, Object> enhanced = Map.of();
        var open = reply.indexOf("<REVIEW_JSON>");
        var close = reply.indexOf("</REVIEW_JSON>");
        if (open >= 0 && close > open) {
            var inner = reply.substring(open + "<REVIEW_JSON>".length(), close).trim();
            try {
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(inner);
                var result = new LinkedHashMap<String, Object>();
                putIfText(result, "title", json.path("title").asText(""));
                putIfText(result, "summary", json.path("summary").asText(""));
                putIfText(result, "petMessage", json.path("petMessage").asText(""));
                putIfArray(result, "highlights", json.path("highlights"));
                putIfArray(result, "risks", json.path("risks"));
                putIfArray(result, "tomorrowSuggestions", json.path("tomorrowSuggestions"));
                enhanced = result;
            } catch (Exception ignored) {
                // Malformed envelope — keep enhanced empty; rule-based defaults will be used.
                // 【信封格式错误——enhanced 保持为空，使用基于规则的默认值。】
            }
        }

        return buildFinalBody(user, completed, rejected, submissionList, todayLogs, todayRecords,
                taskList, minutes, exp, pet, enhanced);
    }

    /**
     * 【构建最终复盘响应体】
     * 流式和非流式路径共用的响应体构建逻辑。拉取基于规则的默认值，
     * 允许 LLM 增强内容逐字段覆盖默认值。
     *
     * @param user          当前用户
     * @param completed     今日完成的任务
     * @param rejected      被打回的任务
     * @param submissionList 今日提交记录
     * @param todayLogs     今日经验日志
     * @param todayRecords  今日学习记录
     * @param taskList      所有任务
     * @param minutes       今日学习分钟数
     * @param exp           今日获得经验值
     * @param pet           用户宠物
     * @param enhanced      LLM 增强数据 Map
     * @return 完整的复盘数据 Map
     *
     * <p>English: Shared body-construction used by both streaming and non-streaming paths. Pulls in
     * the rule-based defaults and lets the LLM enhancement override field-by-field.</p>
     */
    private Map<String, Object> buildFinalBody(com.soulous.auth.UserAccount user,
                                                List<StudyTask> completed, List<StudyTask> rejected,
                                                List<com.soulous.task.TaskSubmission> submissionList,
                                                List<com.soulous.pet.ExpLog> todayLogs,
                                                List<com.soulous.task.StudyRecord> todayRecords,
                                                List<StudyTask> taskList, int minutes, int exp, Pet pet,
                                                Map<String, Object> enhanced) {
        var highlights = new ArrayList<String>();
        if (!completed.isEmpty()) {
            highlights.add("今天完成了 " + completed.size() + " 个已审核通过的学习任务。");
            highlights.add("代表任务：" + completed.getFirst().title);
        }
        if (minutes > 0) highlights.add("记录了 " + minutes + " 分钟学习时间。");
        if (exp > 0) highlights.add("宠物获得 " + exp + " 点经验。");
        if (highlights.isEmpty()) highlights.add("今天还没有形成有效学习记录，可以从一个 15 分钟小任务开始。");

        var risks = new ArrayList<String>();
        if (!rejected.isEmpty()) risks.add("有 " + rejected.size() + " 个任务需要补充凭证或重新提交。");
        if (submissionList.stream().anyMatch(s -> s.status == SubmissionStatus.AI_REJECTED))
            risks.add("存在被 AI 打回的提交，建议补充具体知识点、截图或代码片段。");
        if (minutes == 0) risks.add("今日学习时长仍为 0，统计趋势还没有形成。");
        if (risks.isEmpty()) risks.add("今天节奏稳定，继续保持具体、可验证的学习凭证。");

        var tomorrow = new ArrayList<String>();
        if (!rejected.isEmpty()) tomorrow.add("优先处理被打回或需要补充的任务：" + rejected.getFirst().title);
        taskList.stream().filter(t -> t.status == TaskStatus.TODO || t.status == TaskStatus.DOING).findFirst()
                .ifPresentOrElse(t -> tomorrow.add("继续推进未完成任务：" + t.title),
                        () -> tomorrow.add("创建一个 30 分钟以内的新学习任务，保持连续性。"));
        tomorrow.add("提交凭证时至少写清：学了什么、练了什么、还有什么问题。");

        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("completedTasks", completed.size());
        metrics.put("submissions", submissionList.size());
        metrics.put("studyMinutes", minutes);
        metrics.put("earnedExp", exp);
        metrics.put("petLevel", pet == null ? 1 : pet.level);
        metrics.put("petStatus", pet == null ? PetStatus.NORMAL : pet.status);

        var defaultTitle = completed.isEmpty() ? "今天适合从小任务重新启动" : "今天的学习闭环已经形成";
        var defaultSummary = buildSummary(completed.size(), submissionList.size(), minutes, exp);
        var defaultPetMessage = petMessage(pet, exp, rejected.size());

        var body = new LinkedHashMap<String, Object>();
        body.put("date", LocalDate.now().toString());
        body.put("title", enhanced.getOrDefault("title", defaultTitle));
        body.put("summary", enhanced.getOrDefault("summary", defaultSummary));
        @SuppressWarnings("unchecked")
        var llmHighlights = (List<String>) enhanced.get("highlights");
        @SuppressWarnings("unchecked")
        var llmRisks = (List<String>) enhanced.get("risks");
        @SuppressWarnings("unchecked")
        var llmTomorrow = (List<String>) enhanced.get("tomorrowSuggestions");
        body.put("highlights", llmHighlights == null || llmHighlights.isEmpty() ? highlights : llmHighlights);
        body.put("risks", llmRisks == null || llmRisks.isEmpty() ? risks : llmRisks);
        body.put("tomorrowSuggestions", llmTomorrow == null || llmTomorrow.isEmpty() ? tomorrow : llmTomorrow);
        body.put("petMessage", enhanced.getOrDefault("petMessage", defaultPetMessage));
        body.put("metrics", metrics);
        indexTodayReview(user, body);
        return body;
    }

    /**
     * 【尝试 LLM 增强复盘】
     * 将今日学习数据发送给 LLM，请求生成个性化的中文复盘内容。
     * 包含 RAG 检索到的历史记忆作为参考上下文。LLM 不可用时返回空 Map。
     *
     * @param completed      今日完成任务数
     * @param submissions    今日提交凭证数
     * @param minutes        今日学习分钟数
     * @param exp            今日获得经验值
     * @param rejectedCount  被打回任务数
     * @param allTasks       所有任务列表
     * @param completedTasks 完成的任务列表
     * @param rejectedTasks  被打回的任务列表
     * @param pet            用户宠物
     * @param ragBlock       RAG 检索上下文块（可空）
     * @return LLM 增强数据 Map，失败或不可用时返回空 Map
     */
    private Map<String, Object> tryLlmEnhance(int completed, int submissions, int minutes, int exp,
                                              int rejectedCount, List<StudyTask> allTasks,
                                              List<StudyTask> completedTasks, List<StudyTask> rejectedTasks,
                                              Pet pet, String ragBlock) {
        if (!llm.isAvailable()) return Map.of();
        var system = "你是温和、具体的学习教练。根据用户今日数据生成一份个性化的中文复盘。返回 JSON：title(短句标题)、summary(2-3 句总览)、highlights(2-4 条亮点)、risks(1-3 条需要留意)、tomorrowSuggestions(2-3 条明日建议)、petMessage(对宠物的话, 1 句)。所有字符串简短具体。"
                + (ragBlock == null ? "" : ragBlock);
        var sb = new StringBuilder();
        sb.append("今日数据：\n");
        sb.append("- 完成任务数：").append(completed).append("\n");
        sb.append("- 提交凭证数：").append(submissions).append("\n");
        sb.append("- 学习分钟：").append(minutes).append("\n");
        sb.append("- 获得经验：").append(exp).append("\n");
        sb.append("- 被打回任务数：").append(rejectedCount).append("\n");
        if (!completedTasks.isEmpty()) {
            sb.append("- 完成的代表任务：").append(completedTasks.getFirst().title).append("\n");
        }
        if (!rejectedTasks.isEmpty()) {
            sb.append("- 需要补充的任务：").append(rejectedTasks.getFirst().title).append("\n");
        }
        allTasks.stream()
                .filter(t -> t.status == TaskStatus.TODO || t.status == TaskStatus.DOING)
                .findFirst()
                .ifPresent(t -> sb.append("- 未完成进行中的任务：").append(t.title).append("\n"));
        if (pet != null) {
            sb.append("- 宠物：").append(pet.name).append("，Lv.").append(pet.level).append("，状态 ").append(pet.status).append("\n");
        }
        var json = llm.completeJson(system, sb.toString()).orElse(null);
        if (json == null) return Map.of();
        try {
            var result = new LinkedHashMap<String, Object>();
            putIfText(result, "title", json.path("title").asText(""));
            putIfText(result, "summary", json.path("summary").asText(""));
            putIfText(result, "petMessage", json.path("petMessage").asText(""));
            putIfArray(result, "highlights", json.path("highlights"));
            putIfArray(result, "risks", json.path("risks"));
            putIfArray(result, "tomorrowSuggestions", json.path("tomorrowSuggestions"));
            return result;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /**
     * 【条件写入文本字段】仅当值非空非空白时才写入 Map。
     *
     * @param out   目标 Map
     * @param key   键名
     * @param value 值
     */
    private void putIfText(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) out.put(key, value.trim());
    }

    /**
     * 【条件写入数组字段】仅当 JSON 节点为非空数组时才写入 Map。
     * 过滤掉空白字符串元素。
     *
     * @param out  目标 Map
     * @param key  键名
     * @param node JSON 节点
     */
    private void putIfArray(Map<String, Object> out, String key, com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return;
        var list = new ArrayList<String>();
        for (var item : node) {
            var v = item.asText("");
            if (!v.isBlank()) list.add(v.trim());
        }
        if (!list.isEmpty()) out.put(key, list);
    }

    /**
     * 【构建默认摘要文本】基于今日学习数据生成规则摘要。
     * 无提交时提示用户先完成一个可验证的小任务。
     *
     * @param completed   完成任务数
     * @param submissions 提交凭证数
     * @param minutes     学习分钟数
     * @param exp         获得经验值
     * @return 摘要文本
     */
    private String buildSummary(int completed, int submissions, int minutes, int exp) {
        if (submissions == 0) {
            return "今天还没有提交学习凭证。先完成一个可验证的小任务，Soulous 才能给出更具体的反馈。";
        }
        return "今天提交了 " + submissions + " 次学习凭证，其中 " + completed + " 个任务已完成审核；累计学习 " + minutes + " 分钟，获得 " + exp + " 点经验。";
    }

    /**
     * 【生成宠物寄语】根据宠物状态和今日学习数据生成个性化的宠物消息。
     *
     * @param pet           用户宠物（可空）
     * @param exp           今日获得经验值
     * @param rejectedCount 被打回任务数
     * @return 宠物寄语文本
     */
    private String petMessage(Pet pet, int exp, int rejectedCount) {
        var name = pet == null ? "你的宠物" : pet.name;
        if (exp > 0) {
            return name + " 今天获得了成长能量，继续用具体凭证喂给它更稳定的反馈。";
        }
        if (rejectedCount > 0) {
            return name + " 正在等你补充材料，把打回任务处理掉就能恢复节奏。";
        }
        return name + " 还在安静等待今天的第一份学习凭证。";
    }
}
