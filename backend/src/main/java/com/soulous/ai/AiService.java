package com.soulous.ai;

import com.soulous.auth.UserAccount;
import com.soulous.rag.EmbeddingSourceType;
import com.soulous.rag.RetrievalHit;
import com.soulous.rag.RetrievalService;
import com.soulous.task.Difficulty;
import com.soulous.task.StudyTask;
import com.soulous.task.TaskSubmission;
import com.soulous.task.TaskType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 【AI 业务服务类，封装所有与 AI 相关的业务逻辑，是 LLM 能力与学习场景之间的桥梁。
 * 负责任务审核、追问生成、目标拆解、答案评估等核心 AI 功能。】
 *
 * <p>【设计模式：采用 LLM 优先 + 规则兜底的双层架构。
 * 每个公开方法都先尝试 LLM 调用，失败时自动回退到基于规则的逻辑，
 * 确保系统在 LLM 不可用时仍能正常运行。同时集成 RAG（检索增强生成），
 * 利用用户历史记忆为 LLM 提供个性化上下文。】</p>
 */
@Service
public class AiService {
    /** 【LLM 服务，提供文本完成和 JSON 完成能力】 */
    private final LlmService llm;

    /**
     * 【RAG 检索服务，用于从用户历史中检索相关记忆。
     * 可为 null，使测试或独立调用方无需搭建完整的 RAG 栈。】
     *
     * <p>English: Nullable so tests / standalone callers can construct without the full RAG stack.</p>
     */
    private final RetrievalService retrieval;

    /**
     * 【Spring 自动注入构造函数。注入 LLM 服务和 RAG 检索服务。】
     */
    @Autowired
    public AiService(LlmService llm, RetrievalService retrieval) {
        this.llm = llm;
        this.retrieval = retrieval;
    }

    /**
     * 【测试用便捷构造函数，不依赖 RAG 检索服务。】
     *
     * <p>English: Convenience for tests that don't need RAG.</p>
     */
    public AiService(LlmService llm) {
        this(llm, null);
    }

    /**
     * 【审核学习任务提交。先尝试 LLM 审核（结合 RAG 检索到的用户历史），
     * LLM 不可用或调用失败时回退到基于规则的审核逻辑。
     *
     * <p>LLM 审核优势：能理解提交内容的语义，给出更精准的评分和个性化反馈；
     * 规则兜底：基于文本长度、关键词匹配、任务类型等启发式规则评分。</p>
     *
     * @param task       【学习任务对象，包含标题、描述、类型、难度等信息】
     * @param submission 【用户的任务提交，包含文字证明、代码片段、截图、链接等】
     * @return 【AI 审核结果，包含评分、通过状态、原因和建议】
     */
    public AiReview review(StudyTask task, TaskSubmission submission) {
        var llmReview = tryLlmReview(task, submission);
        if (llmReview != null) return llmReview;
        return ruleBasedReview(task, submission);
    }

    /**
     * 【为学习任务生成检验性追问问题。先尝试 LLM 生成（结合 RAG 避免重复提问），
     * 失败时回退到基于任务类型的预设问题模板。】
     *
     * @param task 【学习任务对象】
     * @return 【追问问题文本】
     */
    public String generateQuestion(StudyTask task) {
        var llmQuestion = tryLlmQuestion(task);
        if (llmQuestion != null && !llmQuestion.isBlank()) return llmQuestion;
        return ruleBasedQuestion(task);
    }

    /**
     * 【将学习目标拆解为 3-5 个可执行的小任务。先尝试 LLM 拆解（结合 RAG 避免重复），
     * 失败时回退到通用的四步学习模板。】
     *
     * @param user 【用户账号，用于 RAG 检索该用户的历史记忆】
     * @param goal 【学习目标描述文本】
     * @return 【拆解结果，包含任务列表】
     */
    public DecomposeResponse decompose(UserAccount user, String goal) {
        var llmTasks = tryLlmDecompose(user, goal);
        if (llmTasks != null && !llmTasks.isEmpty()) return new DecomposeResponse(llmTasks);
        return ruleBasedDecompose(goal);
    }

    /**
     * 【向后兼容的拆解重载方法，不传用户则无法使用 RAG 检索。】
     *
     * <p>English: Back-compat overload (no user → no RAG).</p>
     */
    public DecomposeResponse decompose(String goal) {
        return decompose(null, goal);
    }

    /**
     * 【RAG 检索封装。查询用户的历史记忆（目标记忆、拆解对话、已完成任务、每日复盘），
     * 返回最相关的检索结果列表。RAG 未启用、用户为空或查询为空时返回空列表。
     * 内部捕获所有异常，确保 RAG 故障不影响主流程。】
     *
     * <p>English: Hits returned by {@link #retrieveHits(UserAccount, String)} — empty when RAG off / no match.</p>
     *
     * @param user  【用户账号】
     * @param query 【检索查询文本】
     * @return 【检索命中列表，无结果时为空列表】
     */
    List<RetrievalHit> retrieveHits(UserAccount user, String query) {
        if (retrieval == null || user == null || query == null || query.isBlank()) return List.of();
        if (!retrieval.isEnabled()) return List.of();
        try {
            var hits = retrieval.retrieve(user, query);
            return hits == null ? List.of() : hits;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * 【检索用户历史并渲染为 [RETRIEVED] 文本块，与规划会话 prompt 风格兼容。
     * 无有用结果时返回 null，调用方应跳过该段落而非插入空占位符。】
     *
     * <p>English: Retrieve top hits and render them as a [RETRIEVED] block compatible with the planning
     * session prompt style. Returns {@code null} when nothing useful can be added — callers
     * should then skip the section entirely rather than inserting an empty placeholder.</p>
     *
     * @param user  【用户账号】
     * @param query 【检索查询文本】
     * @return 【渲染后的文本块，无结果时返回 null】
     */
    String buildRetrievedBlock(UserAccount user, String query) {
        return renderRetrievedBlock(retrieveHits(user, query));
    }

    /**
     * 【纯渲染方法——将检索命中列表渲染为带格式的文本块，与 I/O 分离，
     * 使调用方可将同一组 hits 同时用于 prompt 和 API 响应，无需重复检索。
     *
     * <p>渲染格式：
     * <pre>
     * [用户历史相关记忆]
     * [1] 过往目标记忆 (相似度 0.85)
     * 记忆内容摘要...
     * ---
     * [2] 已完成任务 (相似度 0.72)
     * 任务内容摘要...
     * （请把以上历史作为参考语境，但以本次提交本身的事实为准）
     * </pre>
     * </p>
     *
     * @param hits 【检索命中列表】
     * @return 【渲染后的文本块，无结果时返回 null】
     */
    static String renderRetrievedBlock(List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) return null;
        var sb = new StringBuilder();
        sb.append("\n[用户历史相关记忆]\n");
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            if (i > 0) sb.append("\n---\n");
            sb.append("[").append(i + 1).append("] ")
                    .append(hitLabel(h.sourceType()))
                    .append(" (相似度 ").append(String.format(Locale.ROOT, "%.2f", h.similarity())).append(")\n");
            sb.append(truncate(safe(h.content()), 400));
        }
        sb.append("\n（请把以上历史作为参考语境，但以本次提交本身的事实为准）\n");
        return sb.toString();
    }

    /**
     * 【构建 JSON 友好的检索命中视图，用于包含在 AiReview 响应中返回给前端。
     * 每个命中包含 sourceType、sourceId、similarity、snippet 和 label 字段。】
     *
     * <p>English: Build a JSON-friendly view of hits for inclusion in the AiReview response.</p>
     *
     * @param hits 【检索命中列表】
     * @return 【JSON 友好的 Map 列表】
     */
    public static List<java.util.Map<String, Object>> hitsView(List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) return List.of();
        var out = new ArrayList<java.util.Map<String, Object>>(hits.size());
        for (var h : hits) {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("sourceType", h.sourceType().name());
            m.put("sourceId", h.sourceId());
            m.put("similarity", h.similarity());
            m.put("snippet", truncate(safe(h.content()), 200));
            m.put("label", hitLabel(h.sourceType()));
            out.add(m);
        }
        return out;
    }

    /**
     * 【将 EmbeddingSourceType 枚举转换为中文可读标签。
     * 用于检索结果展示，让用户直观了解记忆来源。】
     */
    private static String hitLabel(EmbeddingSourceType t) {
        return switch (t) {
            case GOAL_MEMORY -> "过往目标记忆";
            case SESSION_SUMMARY -> "过往拆解对话";
            case COMPLETED_TASK -> "已完成任务";
            case DAILY_REVIEW -> "过往每日复盘";
        };
    }

    /** 【截断过长字符串，超过 max 字符时截断并添加 "..." 后缀】 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * 【尝试 LLM 审核。构建包含评分准则（rubric）和 RAG 检索结果的 system prompt，
     * 将任务和提交信息格式化为 user prompt，调用 LLM 获取 JSON 格式的审核结果，
     * 然后解析为 AiReview 对象。
     *
     * <p>评分维度：relevanceScore（相关性）、completenessScore（完整度）、
     * qualityScore（质量）和综合 score。结果类型为 PASS/NEED_MORE/REJECT/MANUAL。</p>
     *
     * @param task       【学习任务】
     * @param submission 【用户提交】
     * @return 【AI 审核结果，LLM 不可用或解析失败时返回 null】
     */
    private AiReview tryLlmReview(StudyTask task, TaskSubmission submission) {
        if (!llm.isAvailable()) return null;
        var hits = retrieveHits(task.user, safe(task.title) + " " + safe(task.description));
        var rag = renderRetrievedBlock(hits);
        var system = "你是严谨但鼓励性的学习审核助手。根据用户提交的学习凭证给出评分和反馈。"
                + rubricFor(task.taskType)
                + "返回 JSON，字段：result(\"PASS\"|\"NEED_MORE\"|\"REJECT\")、relevanceScore(0-100)、completenessScore(0-100)、qualityScore(0-100)、score(0-100)、reason(中文 1-2 句)、suggestion(中文 1-2 句)、recommendedExp(0-" + Math.max(1, task.baseExp == null ? 20 : task.baseExp) + ")、needManual(boolean)。"
                + (rag == null ? "" : rag);
        var prompt = "任务：" + safe(task.title) + "\n描述：" + safe(task.description) + "\n类型：" + task.taskType + "，难度：" + task.difficulty + "，课程：" + safe(task.courseName) + "，基础经验：" + (task.baseExp == null ? 20 : task.baseExp)
                + "\n\n用户提交：\n文字：" + safe(submission.textProof)
                + "\n代码：" + safe(submission.codeSnippet)
                + "\n链接：" + safe(submission.proofLink)
                + "\n截图：" + (submission.screenshotUrl == null || submission.screenshotUrl.isBlank() ? "无" : "已上传")
                + "\n学习时长（分）：" + (submission.studyMinutes == null ? 0 : submission.studyMinutes);
        var json = llm.completeJson(system, prompt).orElse(null);
        if (json == null) return null;
        try {
            var review = new AiReview();
            review.submission = submission;
            review.relevanceScore = clampInt(json.path("relevanceScore").asInt(0), 0, 100);
            review.completenessScore = clampInt(json.path("completenessScore").asInt(0), 0, 100);
            review.qualityScore = clampInt(json.path("qualityScore").asInt(0), 0, 100);
            review.score = clampInt(json.path("score").asInt((review.relevanceScore + review.completenessScore + review.qualityScore) / 3), 0, 100);
            var result = json.path("result").asText("NEED_MORE").toUpperCase(Locale.ROOT);
            review.result = switch (result) {
                case "PASS" -> AiReviewResult.PASS;
                case "REJECT" -> AiReviewResult.REJECT;
                case "MANUAL" -> AiReviewResult.MANUAL;
                default -> AiReviewResult.NEED_MORE;
            };
            review.reason = nonBlank(json.path("reason").asText(""), "AI 审核已完成。");
            review.suggestion = nonBlank(json.path("suggestion").asText(""), "请保持当前节奏，下次继续。");
            var baseExp = task.baseExp == null ? 20 : task.baseExp;
            review.recommendedExp = clampInt(json.path("recommendedExp").asInt(baseExp * review.score / 100), 0, baseExp);
            review.needManual = json.path("needManual").asBoolean(review.score >= 45 && review.score < 55);
            review.ragHits = hitsView(hits);
            return review;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 【尝试 LLM 生成追问问题。构建 system prompt 引导 LLM 生成简短的中文追问，
     * 结合 RAG 检索结果避免与用户历史重复提问。
     * 生成的问题会清理前缀（如"问题："）并截断至 120 字符。】
     */
    private String tryLlmQuestion(StudyTask task) {
        if (!llm.isAvailable()) return null;
        var rag = buildRetrievedBlock(task.user, safe(task.title) + " " + safe(task.description));
        var system = "你是温和的学习教练。根据任务给学习者出一个用来检验真实掌握情况的开放式追问问题。中文，单句，不超过 60 字。直接给问题本身，不要任何前缀。"
                + (rag == null ? "" : rag + "若用户历史已涉及相同要点，请换一个角度或更深一层提问。");
        var prompt = "任务：" + safe(task.title) + "\n描述：" + safe(task.description) + "\n类型：" + task.taskType + "，难度：" + task.difficulty;
        var text = llm.complete(system, prompt).orElse(null);
        if (text == null) return null;
        // 【清理 LLM 输出中的"问题："等前缀，保留纯问题文本】
        var cleaned = text.trim().replaceAll("^[问问题题：:\\s]+", "").trim();
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    /**
     * 【尝试 LLM 拆解学习目标。构建 system prompt 引导 LLM 生成 JSON 数组格式的任务列表，
     * 结合 RAG 检索结果避免与用户已完成的任务重复。
     * 每个拆解任务包含标题、描述、类型、难度、预计时长和基础经验。
     * 最多返回 6 个任务，空标题的任务会被跳过。】
     */
    private List<DecomposedTask> tryLlmDecompose(UserAccount user, String goal) {
        if (!llm.isAvailable()) return null;
        var rag = buildRetrievedBlock(user, goal);
        // Object contract {"tasks":[...]} (not a bare array): native JSON mode (response_format
        // json_object) requires a top-level object, and this aligns with the planning-session
        // PLAN_JSON envelope so both decompose paths share one schema.
        var system = "你是学习规划助手。把学习目标拆分成 3-5 个可执行的小任务。返回 JSON 对象，格式 {\"tasks\":[...]}，"
                + "tasks 数组每项字段：title(中文短句)、description(中文 1 句)、taskType(SIMPLE|STUDY|CODING|NOTE|MEMORY|REVIEW)、difficulty(EASY|NORMAL|HARD|CHALLENGE)、estimatedMinutes(整数)、baseExp(整数, 5-50)。"
                + (rag == null ? "" : rag + "如用户已经做过相似内容，避免重复出题，可以在 description 中提示\"已掌握\"或顺势进阶。");
        var prompt = "学习目标：" + (goal == null ? "" : goal.trim());
        var json = llm.completeJsonValidated(system, prompt, AiService::hasUsableTasks).orElse(null);
        if (json == null) return null;
        try {
            var result = new ArrayList<DecomposedTask>();
            for (var item : json.path("tasks")) {
                var title = item.path("title").asText("");
                if (title.isBlank()) continue;
                var desc = item.path("description").asText("");
                var type = parseEnum(TaskType.class, item.path("taskType").asText("STUDY"), TaskType.STUDY);
                var diff = parseEnum(Difficulty.class, item.path("difficulty").asText("NORMAL"), Difficulty.NORMAL);
                var minutes = clampInt(item.path("estimatedMinutes").asInt(30), 5, 240);
                var exp = clampInt(item.path("baseExp").asInt(15), 0, 100);
                result.add(new DecomposedTask(title, desc, type, diff, minutes, exp));
                if (result.size() >= 6) break;
            }
            return result.isEmpty() ? null : result;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 【拆解结果校验谓词：{"tasks":[...]} 中至少有一项带非空 title 才算合格。
     * 不合格时触发 LlmService 的一次自纠重试，而非直接退规则模板。】
     */
    private static boolean hasUsableTasks(com.fasterxml.jackson.databind.JsonNode json) {
        if (json == null) return false;
        var arr = json.path("tasks");
        if (!arr.isArray() || arr.isEmpty()) return false;
        for (var item : arr) {
            if (!item.path("title").asText("").isBlank()) return true;
        }
        return false;
    }

    /**
     * 【根据任务类型生成对应的评分准则（rubric）。不同任务类型有不同的评分侧重点：
     * <ul>
     *   <li>CODING（编程任务）：必须看到代码或截图，缺少时质量分不高于 50</li>
     *   <li>STUDY/NOTE（理论/笔记任务）：以文字深度和条理为主，不要求代码或截图</li>
     *   <li>MEMORY（记忆任务）：以用户复述的完整度为核心</li>
     *   <li>REVIEW（复盘任务）：以反思深度为主——是否指出问题、原因和改进措施</li>
     *   <li>SIMPLE（简单任务）：只要内容真实即可通过，评分上限 70</li>
     * </ul>
     * </p>
     */
    static String rubricFor(TaskType type) {
        if (type == null) return "评分时综合考虑相关性、完整度、质量三方面。";
        return switch (type) {
            case CODING -> "评分准则【编程任务】：必须看到代码片段或运行截图；qualityScore 主要看实现正确性、关键算法/数据结构与可运行性，缺少代码或截图时 qualityScore 不应高于 50；纯文字描述不应给出 PASS。";
            case STUDY, NOTE -> "评分准则【理论/笔记任务】：以文字总结的深度与条理为主要评分依据；不要求代码或截图，只要看到具体知识点、概念对比或个人理解即可给出较高分；不应因为没有截图或代码而扣分。";
            case MEMORY -> "评分准则【记忆/背诵任务】：以用户用自己的话复述或要点列举的完整度为评分核心；可适当看重要点覆盖面与准确性，不强求截图或代码。";
            case REVIEW -> "评分准则【复盘任务】：以反思深度为主——是否指出问题、原因、改进措施；文字描述充分即可，不要求截图或代码。";
            case SIMPLE -> "评分准则【简单任务】：只要内容真实且与任务相关即可通过；评分上限 70 分，避免给出 80 分以上的高分。";
        };
    }

    /**
     * 【安全的枚举解析。尝试将字符串解析为指定枚举值，忽略大小写；
     * 解析失败时返回 fallback 默认值，不抛异常。】
     */
    private <E extends Enum<E>> E parseEnum(Class<E> cls, String value, E fallback) {
        if (value == null) return fallback;
        try { return Enum.valueOf(cls, value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { return fallback; }
    }

    /** 【将整数限制在 [min, max] 范围内】 */
    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 【空安全的字符串回退：null 或空白时返回 fallback】 */
    private String nonBlank(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    /**
     * 【基于规则的审核逻辑，作为 LLM 审核的兜底方案。
     * 通过启发式规则评估提交内容：
     * <ul>
     *   <li>相关性（relevance）：基于任务标题/描述/课程名称与提交文本的关键词匹配度</li>
     *   <li>完整度（completeness）：基于文本长度和学习时长</li>
     *   <li>质量（quality）：基础分 + 文本长度加分 + 学习时长加分 + 编程任务额外加分（截图、代码）</li>
     * </ul>
     * 综合分 = (relevance + completeness + quality) / 3。
     * SIMPLE 类型任务评分上限 70 分。
     * 文本长度 < 6 字符时直接 REJECT。】
     */
    private AiReview ruleBasedReview(StudyTask task, TaskSubmission submission) {
        var text = String.join(" ",
                safe(submission.textProof),
                safe(submission.codeSnippet),
                safe(submission.proofLink));
        int textLength = text.trim().length();
        int relevance = relevance(task, text);
        int completeness = Math.min(100, textLength * 3 + (submission.studyMinutes == null ? 0 : Math.min(30, submission.studyMinutes)));
        int quality = 30;
        if (textLength >= 20) quality += 20;
        if (submission.studyMinutes != null && submission.studyMinutes >= 10) quality += 15;
        boolean codingLike = task.taskType == TaskType.CODING;
        if (codingLike) {
            if (submission.screenshotUrl != null && !submission.screenshotUrl.isBlank()) quality += 15;
            if (submission.codeSnippet != null && submission.codeSnippet.length() > 20) quality += 10;
        } else {
            if (textLength >= 80) quality += 15;
            if (textLength >= 200) quality += 10;
        }
        quality = Math.min(100, quality);
        int score = (relevance + completeness + quality) / 3;
        if (task.taskType == TaskType.SIMPLE) score = Math.min(70, score);

        var review = new AiReview();
        review.submission = submission;
        review.score = score;
        review.relevanceScore = relevance;
        review.completenessScore = completeness;
        review.qualityScore = quality;
        review.recommendedExp = Math.max(0, task.baseExp * score / 100);
        if (textLength < 6) {
            review.result = AiReviewResult.REJECT;
            review.reason = "提交内容过短，无法体现具体学习过程。";
            review.suggestion = "请补充学习内容、知识点、练习结果或截图凭证。";
        } else if (score >= 70) {
            review.result = AiReviewResult.PASS;
            review.reason = "凭证与任务较相关，内容较完整，可信度达到通过标准。";
            review.suggestion = "继续保留具体知识点和练习结果，后续可加入截图或代码提高可信度。";
        } else if (score >= 45) {
            review.result = AiReviewResult.NEED_MORE;
            review.reason = "凭证有一定相关性，但内容仍偏简单。";
            review.suggestion = "建议补充具体知识点、学习时长、截图或代码片段。";
        } else {
            review.result = AiReviewResult.REJECT;
            review.reason = "凭证相关性或完整度不足，暂不建议发放经验。";
            review.suggestion = "请围绕任务标题重新说明学习过程和成果。";
        }
        review.needManual = review.result == AiReviewResult.MANUAL || score >= 45 && score < 55;
        return review;
    }

    /**
     * 【基于规则的追问生成。根据任务类型返回预设的追问问题模板，
     * 每种类型的问题都针对该类型的学习特点设计：
     * <ul>
     *   <li>CODING：询问编程难点和解决方法</li>
     *   <li>NOTE：询问最难理解的知识点</li>
     *   <li>REVIEW：询问发现的偏差和改进计划</li>
     *   <li>MEMORY：要求口述核心内容和记忆方法</li>
     *   <li>SIMPLE：询问最实用的收获和应用例子</li>
     * </ul>
     * </p>
     */
    private String ruleBasedQuestion(StudyTask task) {
        return switch (task.taskType) {
            case CODING -> "你在本次编程练习中遇到的最大难点是什么？用 2-3 句话描述你是如何解决的。";
            case NOTE -> "你整理的笔记中，哪个知识点最难理解？请用自己的话再解释一遍。";
            case REVIEW -> "通过这次复盘，你发现了哪些之前理解有偏差的地方？下次会如何改进？";
            case MEMORY -> "请不看笔记，简要口述你记忆的核心内容，并说明你用了什么记忆方法。";
            case SIMPLE -> "这次学习对你最实用的一个收获是什么？请举一个具体例子说明如何应用。";
            default -> "请说明「" + (task.title.length() > 12 ? task.title.substring(0, 12) : task.title) + "」中你认为最重要的概念，并举一个实际应用场景。";
        };
    }

    /**
     * 【评估用户对追问的回答质量。基于关键词匹配和文本长度计算 0-10 分。
     * 从任务标题、描述和课程名称中提取关键词，统计命中数量，
     * 结合回答长度给出综合评分。】
     *
     * @param task   【学习任务】
     * @param answer 【用户的回答文本】
     * @return 【评分 0-10，回答过短时返回 0】
     */
    public int evaluateAnswer(StudyTask task, String answer) {
        if (answer == null || answer.trim().length() < 10) return 0;
        var trimmed = answer.trim();
        var keywords = (safe(task.title) + " " + safe(task.description) + " " + safe(task.courseName))
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")
                .toLowerCase(Locale.ROOT)
                .split("\\s+");
        int hits = 0;
        var lower = trimmed.toLowerCase(Locale.ROOT);
        for (String word : keywords) {
            if (word.length() >= 2 && lower.contains(word)) hits++;
        }
        int score = Math.min(100, trimmed.length() / 3 + hits * 15);
        return Math.max(0, Math.min(10, score / 10));
    }

    /**
     * 【基于规则的目标拆解。返回通用的四步学习模板：
     * 1. 明确目标（STUDY/EASY）：写下具体问题
     * 2. 学习核心概念（NOTE/NORMAL）：阅读资料整理概念
     * 3. 完成一次练习（CODING/NORMAL）：完成题目或实验
     * 4. 输出学习总结（REVIEW/EASY）：总结收获和计划】
     */
    private DecomposeResponse ruleBasedDecompose(String goal) {
        var clean = goal == null ? "学习任务" : goal.trim();
        var tasks = List.of(
                new DecomposedTask("明确目标：" + clean, "写下本次学习要解决的 2-3 个具体问题。", TaskType.STUDY, Difficulty.EASY, 15, 10),
                new DecomposedTask("学习核心概念", "阅读教材、课程资料或官方文档，整理关键概念。", TaskType.NOTE, Difficulty.NORMAL, 30, 20),
                new DecomposedTask("完成一次练习", "围绕目标完成一道题、一个小实验或一段代码。", TaskType.CODING, Difficulty.NORMAL, 40, 25),
                new DecomposedTask("输出学习总结", "用自己的话总结收获、问题和下一步计划。", TaskType.REVIEW, Difficulty.EASY, 15, 10)
        );
        return new DecomposeResponse(tasks);
    }

    /**
     * 【计算提交文本与任务的相关性分数。从任务标题、描述和课程名称中提取关键词，
     * 统计在提交文本中的命中数量，基础分 45 + 每命中一个关键词加 15 分，上限 100。
     * 仅匹配长度 >= 2 的关键词以避免单字误匹配。】
     */
    private int relevance(StudyTask task, String text) {
        var words = (safe(task.title) + " " + safe(task.description) + " " + safe(task.courseName))
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")
                .toLowerCase(Locale.ROOT)
                .split("\\s+");
        int hits = 0;
        for (String word : words) {
            if (word.length() >= 2 && text.toLowerCase(Locale.ROOT).contains(word)) hits++;
        }
        return Math.min(100, 45 + hits * 15);
    }

    /** 【空安全的字符串处理：null 转为空字符串】 */
    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
