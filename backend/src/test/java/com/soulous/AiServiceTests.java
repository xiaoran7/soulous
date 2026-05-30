package com.soulous;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulous.ai.AiReviewResult;
import com.soulous.ai.AiService;
import com.soulous.ai.LlmService;
import com.soulous.auth.UserAccount;
import com.soulous.rag.EmbeddingSourceType;
import com.soulous.rag.RetrievalHit;
import com.soulous.rag.RetrievalService;
import com.soulous.task.Difficulty;
import com.soulous.task.StudyTask;
import com.soulous.task.TaskSubmission;
import com.soulous.task.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【AiService 的单元测试，覆盖 AI 审核、任务分解、题目生成等核心功能。
 *  使用 StubLlm 和 StubRetrieval 桩对象隔离外部依赖。
 *  覆盖场景：
 *  - 审核：LLM 有效 JSON 输出、分数夹紧、未知结果映射、LLM 不可用降级、
 *    空 JSON 降级、缺失字段默认值、prompt 中注入不同任务类型评分标准
 *  - 分解：LLM 有效任务数组、夹紧分钟数/回退未知枚举、空数组降级、跳过空白标题
 *  - 题目生成：LLM 文本去前缀、空白回退、LLM 不可用回退
 *  - RAG 注入：有命中时注入历史记忆块、无命中/禁用/异常时省略】
 *
 * <p>Unit tests for AiService covering review, decompose, and generateQuestion.</p>
 */
class AiServiceTests {

    /** Stub LlmService with controllable availability + scripted JSON/text responses.
     *  【可控制可用性和预设响应的 LlmService 桩实现】
     */
    static class StubLlm extends LlmService {
        private final ObjectMapper mapper = new ObjectMapper();
        boolean available = true;     // 【控制 LLM 是否可用】
        String jsonResponse;          // 【预设的 JSON 响应字符串】
        String textResponse;          // 【预设的文本响应字符串】
        boolean throwOnCall;          // 【调用时是否返回空（模拟失败）】
        String lastSystem;            // 【记录最近一次调用的 system prompt】
        String lastUser;              // 【记录最近一次调用的 user prompt】

        StubLlm() {
            super("openai", "stub-key", "stub-model", "", 30, false, 16, 60);
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Optional<String> complete(String system, String user) {
            lastSystem = system;
            lastUser = user;
            if (!available) return Optional.empty();
            if (throwOnCall) return Optional.empty();
            return Optional.ofNullable(textResponse);
        }

        @Override
        public Optional<JsonNode> completeJson(String system, String user) {
            lastSystem = system;
            lastUser = user;
            if (!available) return Optional.empty();
            if (throwOnCall || jsonResponse == null) return Optional.empty();
            try {
                return Optional.of(mapper.readTree(jsonResponse));
            } catch (Exception ex) {
                return Optional.empty();
            }
        }
    }

    /** Stub RetrievalService that bypasses repository/embedding wiring.
     *  【绕过仓库/向量化依赖的 RetrievalService 桩实现】
     */
    static class StubRetrieval extends RetrievalService {
        boolean enabled = true;       // 【RAG 是否启用】
        List<RetrievalHit> hits = List.of();  // 【预设的检索结果】
        boolean throwOnRetrieve;      // 【检索时是否抛异常】
        String lastQuery;             // 【记录最近一次检索的查询文本】
        StubRetrieval() { super(null, null, null); }
        @Override public boolean isEnabled() { return enabled; }
        @Override public List<RetrievalHit> retrieve(UserAccount user, String query, int topK, double minSim) {
            lastQuery = query;
            if (throwOnRetrieve) throw new RuntimeException("boom");
            return hits;
        }
        @Override public List<RetrievalHit> retrieve(UserAccount user, String query) {
            return retrieve(user, query, 0, Double.NaN);
        }
    }

    /**
     * 【辅助方法：创建测试用户】
     */
    private static UserAccount user() {
        var u = new UserAccount();
        u.id = 1L;
        return u;
    }

    /**
     * 【辅助方法：创建指定标题、类型、基础经验的任务】
     */
    private static StudyTask task(String title, TaskType type, int baseExp) {
        var t = new StudyTask();
        t.title = title;
        t.description = "学习" + title + "的核心概念";
        t.taskType = type;
        t.difficulty = Difficulty.NORMAL;
        t.courseName = "测试课程";
        t.baseExp = baseExp;
        return t;
    }

    /**
     * 【辅助方法：创建指定文本和学习分钟数的提交】
     */
    private static TaskSubmission submission(String text, Integer minutes) {
        var s = new TaskSubmission();
        s.textProof = text;
        s.studyMinutes = minutes;
        return s;
    }

    /**
     * 【测试 LLM 返回有效 JSON 时正确解析审核结果。
     *  验证 result=PASS、score=86、relevanceScore=88、
     *  reason 包含"凭证清晰"、recommendedExp=18、needManual=false。】
     */
    @Test
    void reviewUsesLlmOutputWhenJsonIsValid() {
        var llm = new StubLlm();
        llm.jsonResponse = """
                {
                  "result": "PASS",
                  "relevanceScore": 88,
                  "completenessScore": 90,
                  "qualityScore": 80,
                  "score": 86,
                  "reason": "凭证清晰，学习过程完整。",
                  "suggestion": "继续保持。",
                  "recommendedExp": 18,
                  "needManual": false
                }
                """;
        var ai = new AiService(llm);

        var r = ai.review(task("Java 流", TaskType.CODING, 20),
                submission("写了一段 Stream 的 reduce 示例并理解了惰性求值", 45));

        assertThat(r.result).isEqualTo(AiReviewResult.PASS);
        assertThat(r.score).isEqualTo(86);
        assertThat(r.relevanceScore).isEqualTo(88);
        assertThat(r.reason).contains("凭证清晰");
        assertThat(r.recommendedExp).isEqualTo(18);
        assertThat(r.needManual).isFalse();
    }

    /**
     * 【测试 LLM 返回超出范围的分数值被夹紧到有效范围 [0, 100]。
     *  relevanceScore=150→100, completenessScore=-30→0, qualityScore=200→100,
     *  score=999→100, recommendedExp=9999→baseExp(25)。】
     */
    @Test
    void reviewClampsLlmScoresToValidRange() {
        var llm = new StubLlm();
        llm.jsonResponse = """
                {"result":"PASS","relevanceScore":150,"completenessScore":-30,
                 "qualityScore":200,"score":999,"recommendedExp":9999,"needManual":false}
                """;
        var ai = new AiService(llm);
        var r = ai.review(task("X", TaskType.STUDY, 25), submission("xxxxxxxxxxxx", 10));

        assertThat(r.relevanceScore).isBetween(0, 100);
        assertThat(r.completenessScore).isBetween(0, 100);
        assertThat(r.qualityScore).isBetween(0, 100);
        assertThat(r.score).isBetween(0, 100);
        assertThat(r.recommendedExp).isBetween(0, 25);
    }

    /**
     * 【测试 LLM 返回未知 result 值（"WHATEVER"）时映射为 NEED_MORE。】
     */
    @Test
    void reviewMapsUnknownResultToNeedMore() {
        var llm = new StubLlm();
        llm.jsonResponse = """
                {"result":"WHATEVER","relevanceScore":50,"completenessScore":50,
                 "qualityScore":50,"score":50,"reason":"r","suggestion":"s",
                 "recommendedExp":10,"needManual":false}
                """;
        var r = new AiService(llm).review(task("X", TaskType.STUDY, 20),
                submission("足够长的提交内容用来满足判断", 20));
        assertThat(r.result).isEqualTo(AiReviewResult.NEED_MORE);
    }

    /**
     * 【测试 LLM 不可用时降级到规则引擎审核。
     *  验证结果为 PASS 或 NEED_MORE，分数在 [0,100] 范围，reason 和 suggestion 非空。】
     */
    @Test
    void reviewFallsBackToRulesWhenLlmUnavailable() {
        var llm = new StubLlm();
        llm.available = false;
        var ai = new AiService(llm);

        var t = task("Java 流 Stream", TaskType.CODING, 30);
        var s = submission("我学习了 Java 流 Stream 的用法，写了 reduce 和 collect 各一例", 45);
        s.codeSnippet = "list.stream().reduce(0, Integer::sum)";

        var r = ai.review(t, s);

        assertThat(r.result).isIn(AiReviewResult.PASS, AiReviewResult.NEED_MORE);
        assertThat(r.score).isBetween(0, 100);
        assertThat(r.reason).isNotBlank();
        assertThat(r.suggestion).isNotBlank();
    }

    /**
     * 【测试 LLM 返回空 JSON 时降级：提交内容过短（"too"）触发 REJECT 结果，
     *  reason 包含"过短"。】
     */
    @Test
    void reviewFallsBackWhenLlmReturnsEmptyJson() {
        var llm = new StubLlm();
        llm.jsonResponse = null; // completeJson returns empty
        var ai = new AiService(llm);

        var r = ai.review(task("X", TaskType.STUDY, 20),
                submission("too", 1));

        assertThat(r.result).isEqualTo(AiReviewResult.REJECT);
        assertThat(r.reason).contains("过短");
    }

    /**
     * 【测试 LLM JSON 缺少 score 字段时使用默认值（0分），
     *  result 默认为 NEED_MORE，reason 和 suggestion 仍有值。】
     */
    @Test
    void reviewFallsBackWhenLlmJsonIsMissingFields() {
        var llm = new StubLlm();
        // No score fields — review should still get built using defaults (0s),
        // but since this is the "valid JSON" path, it goes through LLM branch with zeroed scores.
        llm.jsonResponse = "{}";
        var ai = new AiService(llm);

        var r = ai.review(task("X", TaskType.STUDY, 20),
                submission("一段提交内容用来跑通流程", 15));

        // Defaults: result=NEED_MORE, scores=0, recommendedExp=0.
        assertThat(r.result).isEqualTo(AiReviewResult.NEED_MORE);
        assertThat(r.score).isEqualTo(0);
        assertThat(r.reason).isNotBlank();
        assertThat(r.suggestion).isNotBlank();
    }

    /**
     * 【测试 LLM 返回非空任务数组时正确解析：2 个任务，
     *  第一个标题为"读概念"，第二个 taskType=CODING、estimatedMinutes=40。】
     */
    @Test
    void decomposeUsesLlmTasksWhenArrayNonEmpty() {
        var llm = new StubLlm();
        llm.jsonResponse = """
                {"tasks":[
                  {"title":"读概念","description":"看文档","taskType":"NOTE",
                   "difficulty":"EASY","estimatedMinutes":20,"baseExp":10},
                  {"title":"写练习","description":"写一段代码","taskType":"CODING",
                   "difficulty":"NORMAL","estimatedMinutes":40,"baseExp":25}
                ]}
                """;
        var resp = new AiService(llm).decompose("掌握 Spring Boot");

        assertThat(resp.tasks()).hasSize(2);
        assertThat(resp.tasks().get(0).title()).isEqualTo("读概念");
        assertThat(resp.tasks().get(1).taskType()).isEqualTo(TaskType.CODING);
        assertThat(resp.tasks().get(1).estimatedMinutes()).isEqualTo(40);
    }

    /**
     * 【测试分解任务时夹紧超长分钟数（9999→[5,240]），
     *  无效枚举回退到默认值（taskType: NOPE→STUDY, difficulty: GODMODE→NORMAL）。】
     */
    @Test
    void decomposeClampsMinutesAndFallsBackOnUnknownEnums() {
        var llm = new StubLlm();
        llm.jsonResponse = """
                {"tasks":[{"title":"超长任务","description":"d","taskType":"NOPE",
                  "difficulty":"GODMODE","estimatedMinutes":9999,"baseExp":15}]}
                """;
        var resp = new AiService(llm).decompose("x");

        assertThat(resp.tasks()).hasSize(1);
        var only = resp.tasks().get(0);
        assertThat(only.taskType()).isEqualTo(TaskType.STUDY);
        assertThat(only.difficulty()).isEqualTo(Difficulty.NORMAL);
        assertThat(only.estimatedMinutes()).isBetween(5, 240);
    }

    /**
     * 【测试 LLM 返回空数组时降级到规则引擎：生成的任务标题包含"React Hooks"。】
     */
    @Test
    void decomposeFallsBackToRulesWhenLlmReturnsEmptyArray() {
        var llm = new StubLlm();
        llm.jsonResponse = "{\"tasks\":[]}";
        var resp = new AiService(llm).decompose("掌握 React Hooks");

        assertThat(resp.tasks()).isNotEmpty();
        assertThat(resp.tasks().get(0).title()).contains("React Hooks");
    }

    /**
     * 【测试分解任务时跳过标题为空的任务项，只保留有效任务。】
     */
    @Test
    void decomposeSkipsItemsWithBlankTitle() {
        var llm = new StubLlm();
        llm.jsonResponse = """
                {"tasks":[{"title":"","description":"x"},
                 {"title":"有效任务","description":"d","taskType":"STUDY",
                  "difficulty":"NORMAL","estimatedMinutes":30,"baseExp":15}]}
                """;
        var resp = new AiService(llm).decompose("x");
        assertThat(resp.tasks()).hasSize(1);
        assertThat(resp.tasks().get(0).title()).isEqualTo("有效任务");
    }

    /**
     * 【测试题目生成使用 LLM 文本并去除"问题："前缀。
     *  LLM 返回"问题：你如何理解..."，结果应为"你如何理解..."。】
     */
    @Test
    void generateQuestionUsesLlmTextAndStripsPrefix() {
        var llm = new StubLlm();
        llm.textResponse = "问题：你如何理解 Stream 的惰性求值？";
        var q = new AiService(llm).generateQuestion(task("Stream", TaskType.CODING, 20));
        assertThat(q).isEqualTo("你如何理解 Stream 的惰性求值？");
    }

    /**
     * 【测试 LLM 返回空白文本时降级，生成的题目包含"编程练习"。】
     */
    @Test
    void generateQuestionFallsBackOnBlankLlmText() {
        var llm = new StubLlm();
        llm.textResponse = "   ";
        var q = new AiService(llm).generateQuestion(task("Stream", TaskType.CODING, 20));
        assertThat(q).contains("编程练习");
    }

    /**
     * 【测试 CODING 类型任务的审核 prompt 注入编程评分标准，
     *  system prompt 包含"编程任务"和"代码片段"关键词。】
     */
    @Test
    void reviewPromptInjectsCodingRubricForCodingTasks() {
        var llm = new StubLlm();
        llm.jsonResponse = "{\"result\":\"PASS\",\"score\":80,\"recommendedExp\":15}";
        new AiService(llm).review(task("写算法", TaskType.CODING, 20),
                submission("提交内容", 30));
        assertThat(llm.lastSystem).contains("编程任务");
        assertThat(llm.lastSystem).contains("代码片段");
    }

    /**
     * 【测试 STUDY 和 NOTE 类型任务的审核 prompt 注入理论/笔记评分标准，
     *  不包含"必须看到代码片段"要求。】
     */
    @Test
    void reviewPromptInjectsTheoryRubricForStudyAndNoteTasks() {
        var llmStudy = new StubLlm();
        llmStudy.jsonResponse = "{}";
        new AiService(llmStudy).review(task("理论", TaskType.STUDY, 20),
                submission("提交内容", 30));
        assertThat(llmStudy.lastSystem).contains("理论/笔记任务");
        assertThat(llmStudy.lastSystem).doesNotContain("必须看到代码片段");

        var llmNote = new StubLlm();
        llmNote.jsonResponse = "{}";
        new AiService(llmNote).review(task("笔记", TaskType.NOTE, 20),
                submission("提交内容", 30));
        assertThat(llmNote.lastSystem).contains("理论/笔记任务");
    }

    /**
     * 【测试 MEMORY 和 REVIEW 类型任务的审核 prompt 注入对应评分标准。
     *  MEMORY 任务 prompt 包含"记忆/背诵任务"，REVIEW 包含"复盘任务"。】
     */
    @Test
    void reviewPromptInjectsMemoryAndReviewRubrics() {
        var llmMem = new StubLlm();
        llmMem.jsonResponse = "{}";
        new AiService(llmMem).review(task("背诵", TaskType.MEMORY, 20),
                submission("提交内容", 30));
        assertThat(llmMem.lastSystem).contains("记忆/背诵任务");

        var llmRev = new StubLlm();
        llmRev.jsonResponse = "{}";
        new AiService(llmRev).review(task("复盘", TaskType.REVIEW, 20),
                submission("提交内容", 30));
        assertThat(llmRev.lastSystem).contains("复盘任务");
    }

    /**
     * 【测试 SIMPLE 类型任务的审核 prompt 中分数上限为 70 分。】
     */
    @Test
    void reviewPromptCapsSimpleTaskRubricAt70() {
        var llm = new StubLlm();
        llm.jsonResponse = "{}";
        new AiService(llm).review(task("简单任务", TaskType.SIMPLE, 10),
                submission("提交内容", 10));
        assertThat(llm.lastSystem).contains("简单任务");
        assertThat(llm.lastSystem).contains("70 分");
    }

    /**
     * 【测试规则引擎审核不因缺少截图而惩罚理论任务。
     *  理论任务（STUDY）获得文本深度加分，分数应 >= 编程任务（CODING）。】
     */
    @Test
    void ruleBasedReviewDoesNotPenalizeTheoryTaskForMissingScreenshot() {
        var llm = new StubLlm();
        llm.available = false;
        var ai = new AiService(llm);

        var deepText = "本次复习栈与队列：".repeat(20); // textLength > 200
        var theory = ai.review(task("栈与队列", TaskType.STUDY, 30),
                submission(deepText, 25));
        var coding = ai.review(task("栈与队列", TaskType.CODING, 30),
                submission(deepText, 25));

        // Theory should not be penalized for missing screenshot/code:
        // it gets text-depth bonuses instead. With this submission, theory score
        // should be at least as good as coding's.
        assertThat(theory.qualityScore).isGreaterThanOrEqualTo(coding.qualityScore);
    }

    /**
     * 【测试 LLM 不可用时题目生成降级：生成的题目包含"笔记"关键词。】
     */
    @Test
    void generateQuestionFallsBackWhenLlmUnavailable() {
        var llm = new StubLlm();
        llm.available = false;
        var q = new AiService(llm).generateQuestion(task("笔记整理", TaskType.NOTE, 15));
        assertThat(q).contains("笔记");
    }

    // ----- RAG injection ----------------------------------------------------
    // 【RAG 注入测试区域】

    /**
     * 【测试有 RAG 检索命中时，审核 prompt 注入用户历史相关记忆块。
     *  验证 system prompt 包含 [用户历史相关记忆]、已完成任务、过往目标记忆、
     *  具体检索内容（reduce/collect），且检索查询包含任务标题。】
     */
    @Test
    void reviewPromptIncludesRagBlockWhenHitsAvailable() {
        var llm = new StubLlm();
        llm.jsonResponse = "{\"result\":\"PASS\",\"score\":80,\"recommendedExp\":15}";
        var rag = new StubRetrieval();
        rag.hits = List.of(
                new RetrievalHit(EmbeddingSourceType.COMPLETED_TASK, 7L, "上周完成的 Stream 练习：reduce/collect 示例", 0.82),
                new RetrievalHit(EmbeddingSourceType.GOAL_MEMORY, 3L, "目标：吃透 Java Stream", 0.71)
        );
        var t = task("Stream 深入", TaskType.CODING, 30);
        t.user = user();

        new AiService(llm, rag).review(t, submission("本次写了 flatMap 用法", 30));

        assertThat(llm.lastSystem).contains("[用户历史相关记忆]");
        assertThat(llm.lastSystem).contains("已完成任务");
        assertThat(llm.lastSystem).contains("过往目标记忆");
        assertThat(llm.lastSystem).contains("reduce/collect");
        assertThat(rag.lastQuery).contains("Stream 深入");
    }

    /**
     * 【测试 RAG 检索结果为空时，审核 prompt 不包含 [用户历史相关记忆] 块。】
     */
    @Test
    void reviewPromptOmitsRagBlockWhenRetrievalEmpty() {
        var llm = new StubLlm();
        llm.jsonResponse = "{}";
        var rag = new StubRetrieval();
        rag.hits = List.of();
        var t = task("X", TaskType.STUDY, 20);
        t.user = user();

        new AiService(llm, rag).review(t, submission("提交内容", 10));

        assertThat(llm.lastSystem).doesNotContain("[用户历史相关记忆]");
    }

    /**
     * 【测试 RAG 禁用时（enabled=false），即使有检索结果也不注入记忆块。】
     */
    @Test
    void reviewPromptOmitsRagBlockWhenRagDisabled() {
        var llm = new StubLlm();
        llm.jsonResponse = "{}";
        var rag = new StubRetrieval();
        rag.enabled = false;
        rag.hits = List.of(new RetrievalHit(EmbeddingSourceType.COMPLETED_TASK, 1L, "x", 0.9));
        var t = task("X", TaskType.STUDY, 20);
        t.user = user();

        new AiService(llm, rag).review(t, submission("提交内容", 10));

        assertThat(llm.lastSystem).doesNotContain("[用户历史相关记忆]");
    }

    /**
     * 【测试 RAG 检索异常时吞噬异常，仍正常调用 LLM。
     *  验证审核结果为 PASS，prompt 中不包含记忆块。】
     */
    @Test
    void reviewSwallowsRetrievalFailureAndStillCallsLlm() {
        var llm = new StubLlm();
        llm.jsonResponse = "{\"result\":\"PASS\",\"score\":70,\"recommendedExp\":10}";
        var rag = new StubRetrieval();
        rag.throwOnRetrieve = true;
        var t = task("X", TaskType.STUDY, 20);
        t.user = user();

        var r = new AiService(llm, rag).review(t, submission("提交内容", 10));

        assertThat(r.result).isEqualTo(AiReviewResult.PASS);
        assertThat(llm.lastSystem).doesNotContain("[用户历史相关记忆]");
    }

    /**
     * 【测试分解任务时有 RAG 命中，prompt 注入记忆块并包含"已掌握"关键词，
     *  检索查询为目标标题。】
     */
    @Test
    void decomposePromptIncludesRagBlockWhenHitsAvailable() {
        var llm = new StubLlm();
        llm.jsonResponse = "{\"tasks\":[{\"title\":\"new task\",\"description\":\"d\",\"taskType\":\"STUDY\",\"difficulty\":\"NORMAL\",\"estimatedMinutes\":30,\"baseExp\":10}]}";
        var rag = new StubRetrieval();
        rag.hits = List.of(new RetrievalHit(EmbeddingSourceType.COMPLETED_TASK, 1L, "已完成过 Hooks 基础", 0.78));

        new AiService(llm, rag).decompose(user(), "深入 React Hooks");

        assertThat(llm.lastSystem).contains("[用户历史相关记忆]");
        assertThat(llm.lastSystem).contains("已掌握");
        assertThat(rag.lastQuery).isEqualTo("深入 React Hooks");
    }

    /**
     * 【测试题目生成时有 RAG 命中，prompt 注入记忆块并包含"换一个角度"关键词。】
     */
    @Test
    void generateQuestionPromptIncludesRagBlockWhenHitsAvailable() {
        var llm = new StubLlm();
        llm.textResponse = "请讲一下 reduce 的累加器初值意义。";
        var rag = new StubRetrieval();
        rag.hits = List.of(new RetrievalHit(EmbeddingSourceType.COMPLETED_TASK, 1L, "上次写过 reduce", 0.81));
        var t = task("Stream", TaskType.CODING, 20);
        t.user = user();

        new AiService(llm, rag).generateQuestion(t);

        assertThat(llm.lastSystem).contains("[用户历史相关记忆]");
        assertThat(llm.lastSystem).contains("换一个角度");
    }

    /**
     * 【测试向后兼容的单参数构造函数（无 RetrievalService）正常工作。
     *  旧版测试使用的构造函数，null retrieval 意味着 RAG 静默跳过。
     *  验证审核结果为 PASS，prompt 中不包含记忆块。】
     */
    @Test
    void backCompatConstructorWorksWithoutRetrieval() {
        // The legacy single-arg constructor (used by older tests) must keep working —
        // null retrieval means RAG is silently skipped.
        var llm = new StubLlm();
        llm.jsonResponse = "{\"result\":\"PASS\",\"score\":80,\"recommendedExp\":15}";
        var t = task("X", TaskType.STUDY, 20);
        t.user = user();

        var r = new AiService(llm).review(t, submission("提交内容", 10));

        assertThat(r.result).isEqualTo(AiReviewResult.PASS);
        assertThat(llm.lastSystem).doesNotContain("[用户历史相关记忆]");
    }
}
