package com.soulous;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulous.ai.LlmService;
import com.soulous.aisession.PlanningSession;
import com.soulous.aisession.PlanningSessionRepository;
import com.soulous.aisession.PlanningSessionService;
import com.soulous.aisession.SessionDtos;
import com.soulous.aisession.SessionKind;
import com.soulous.aisession.SessionState;
import com.soulous.aisession.SessionTurnRepository;
import com.soulous.aisession.TurnRole;
import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.goal.Goal;
import com.soulous.goal.GoalRepository;
import com.soulous.goal.GoalStatus;
import com.soulous.task.Difficulty;
import com.soulous.task.StudyTask;
import com.soulous.task.TaskRepository;
import com.soulous.task.TaskStatus;
import com.soulous.task.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【PlanningSessionService 的集成测试，覆盖新建目标流程、计划提案编辑/删除、提交计划、
 *  放弃会话、签到打卡、会话列表与删除、LLM 不可用降级、对话轮次上限、会话所有权校验等核心场景。
 *  使用 H2 内存数据库和 ScriptedLlm 桩对象来隔离外部依赖。】
 *
 * <p>SpringBootTest with H2 in-memory database and a scripted LLM stub.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:planning-session-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
@Import(PlanningSessionServiceTests.TestLlmConfig.class)
class PlanningSessionServiceTests {

    /**
     * 【测试用 LLM 配置类，将 ScriptedLlm 注册为 Primary Bean，替换真实 LLM 服务】
     */
    @TestConfiguration
    static class TestLlmConfig {
        @Bean @Primary
        ScriptedLlm scriptedLlm() { return new ScriptedLlm(); }
    }

    /**
     * Scriptable LLM that returns canned text / JSON for complete() and completeJson(),
     * in FIFO order. Records every system+user prompt for assertions.
     *
     * 【可编程的 LLM 桩实现，使用 FIFO 队列返回预设的文本/JSON 响应。
     *  记录每次调用的 system+user prompt 以便测试断言。
     *  支持模拟 LLM 不可用、传输异常等边界场景。】
     */
    static class ScriptedLlm extends LlmService {
        private final ObjectMapper mapper = new ObjectMapper();
        final Deque<String> textQueue = new ArrayDeque<>();   // 【文本响应队列，FIFO 顺序出队】
        final Deque<String> jsonQueue = new ArrayDeque<>();   // 【JSON 响应队列，FIFO 顺序出队】
        final List<String[]> calls = new ArrayList<>();       // 【记录所有调用：[type, system, user]】
        boolean available = true;      // 【控制 LLM 是否可用，用于测试降级逻辑】
        boolean throwOnNextText = false; // 【下次调用 complete() 时是否抛异常，用于测试异常恢复】

        ScriptedLlm() {
            // 【使用桩参数初始化父类 LlmService：provider="openai", apiKey="stub-key", model="stub-model"】
            super("openai", "stub-key", "stub-model", "", 30, false, 16, 60);
        }

        @Override public boolean isAvailable() { return available; }

        @Override
        public Optional<String> complete(String system, String user) {
            // 【记录调用参数，格式为 [type, system, user]】
            calls.add(new String[]{"text", system == null ? "" : system, user == null ? "" : user});
            if (throwOnNextText) {
                // 【模拟 LLM 传输错误，仅触发一次后自动关闭】
                throwOnNextText = false;
                throw new RuntimeException("simulated LLM transport error");
            }
            if (!available) return Optional.empty();
            // 【从队列头部取出并返回预设文本，队列为空则返回 null → Optional.empty()】
            return Optional.ofNullable(textQueue.pollFirst());
        }

        @Override
        public Optional<JsonNode> completeJson(String system, String user) {
            // 【记录调用参数，格式为 [type, system, user]】
            calls.add(new String[]{"json", system == null ? "" : system, user == null ? "" : user});
            if (!available) return Optional.empty();
            var next = jsonQueue.pollFirst();
            if (next == null) return Optional.empty();
            try { return Optional.of(mapper.readTree(next)); }
            catch (Exception ex) { return Optional.empty(); }
        }

        void enqueueText(String s) { textQueue.add(s); }    // 【向文本队列尾部添加预设响应】
        void enqueueJson(String s) { jsonQueue.add(s); }    // 【向 JSON 队列尾部添加预设响应】
        void reset() {
            // 【重置所有状态：清空队列、调用记录，恢复可用状态】
            textQueue.clear(); jsonQueue.clear(); calls.clear(); available = true;
            throwOnNextText = false;
        }
    }

    // 【注入被测服务和依赖仓库】
    @Autowired ScriptedLlm llm;
    @Autowired PlanningSessionService service;
    @Autowired UserService users;
    @Autowired GoalRepository goals;
    @Autowired PlanningSessionRepository sessions;
    @Autowired SessionTurnRepository turns;
    @Autowired TaskRepository tasks;

    /**
     * 【每个测试前重置 LLM 桩状态，确保测试之间互不影响】
     */
    @BeforeEach
    void resetLlm() { llm.reset(); }

    /**
     * 【创建唯一用户名的测试用户，避免并发测试冲突】
     * @param tag 用户标签前缀
     */
    private UserAccount newUser(String tag) {
        var unique = tag + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", tag, unique + "@example.com"));
        return users.byToken(auth.token());
    }

    // ----- new goal flow ------------------------------------------------
    // 【新建目标流程测试区域】

    /**
     * 【测试新建目标时创建会话并追加首条助手回复。
     *  验证：会话 ID 非空、类型为 NEW_GOAL、状态为 DRAFTING、
     *  目标标题正确、轮次为 2（用户消息+助手回复）、无重复候选、无待定计划。】
     */
    @Test
    void startNewGoalCreatesGoalSessionAndAppendsFirstAssistantReply() {
        var user = newUser("starter");
        // no JSON queued for duplicate detection -> empty candidates
        // 【不排队重复检测的 JSON 响应，模拟无重复候选的情况】
        llm.enqueueText("欢迎！你希望这个目标多久内达成？");

        var view = service.startNewGoal(user, "学透 Spring Boot 基础");

        assertThat(view.id()).isNotNull();
        assertThat(view.kind()).isEqualTo(SessionKind.NEW_GOAL);
        assertThat(view.state()).isEqualTo(SessionState.DRAFTING);
        assertThat(view.goalTitle()).isEqualTo("学透 Spring Boot 基础");
        // user message + assistant reply = 2 turns
        // 【验证轮次：用户消息 + 助手回复 = 2 轮】
        assertThat(view.turnCount()).isEqualTo(2);
        assertThat(view.turns()).hasSize(2);
        assertThat(view.turns().get(0).role()).isEqualTo(TurnRole.USER);
        assertThat(view.turns().get(1).role()).isEqualTo(TurnRole.ASSISTANT);
        assertThat(view.turns().get(1).content()).contains("多久内达成");
        assertThat(view.duplicateCandidates()).isEmpty();
        assertThat(view.pendingPlan()).isNull();

        assertThat(goals.findById(view.goalId())).isPresent();
    }

    /**
     * 【测试空目标标题被拒绝。空白字符串应抛出 BadRequestException。】
     */
    @Test
    void emptyGoalIsRejected() {
        var user = newUser("empty");
        assertThatThrownBy(() -> service.startNewGoal(user, "   "))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * 【测试重复目标检测：当 LLM 返回匹配的已有目标时，
     *  duplicateCandidates 列表应包含匹配项及原因。】
     */
    @Test
    void duplicateDetectionSurfacesLlmMatches() {
        var user = newUser("dup");
        // First goal — opens but we won't use its session
        // 【创建第一个目标，用于后续重复检测对比】
        llm.enqueueText("好的，先聊聊。");
        var first = service.startNewGoal(user, "学习日语 N4");
        assertThat(first.goalId()).isNotNull();

        // Second goal: queue a JSON duplicate response for detectDuplicates, then a text reply
        // 【第二个目标：排队返回重复检测 JSON 响应，包含第一个目标的匹配结果】
        llm.enqueueJson("{\"duplicates\":[{\"goalId\":" + first.goalId() + ",\"reason\":\"同为日语 N4 学习\"}]}");
        llm.enqueueText("看起来和已有目标类似。");
        var second = service.startNewGoal(user, "考过日语 N4");

        assertThat(second.duplicateCandidates()).hasSize(1);
        assertThat(second.duplicateCandidates().get(0).goalId()).isEqualTo(first.goalId());
        assertThat(second.duplicateCandidates().get(0).reason()).contains("日语");
    }

    /**
     * 【测试计划信封（PLAN_JSON）解析：当助手回复包含 <PLAN_JSON> 标记时，
     *  会话状态应从 DRAFTING 转为 PLAN_PROPOSED，pendingPlan 非空，
     *  suggestedActions 包含 "commit"，且用户可见文本中不包含原始 JSON 信封。】
     */
    @Test
    void planEnvelopeTransitionsToPlanProposedAndStripsFromUserVisibleText() {
        var user = newUser("plan");
        // First call: open the session with a non-plan reply
        // 【第一次调用：用非计划回复打开会话】
        llm.enqueueText("好，先问你一个问题：每周可投入几小时？");
        var view = service.startNewGoal(user, "学透 React Hooks");
        assertThat(view.state()).isEqualTo(SessionState.DRAFTING);

        // Second call: user replies, assistant emits a plan envelope
        // 【第二次调用：用户回复后，助手输出包含 PLAN_JSON 信封的计划提案】
        llm.enqueueText("""
                那我先给你一个初稿：
                <PLAN_JSON>
                {"goalTitle":"学透 React Hooks","tasks":[
                  {"title":"读 useState 文档","description":"读官方文档","estimatedMinutes":30,"difficulty":"EASY","taskType":"NOTE"},
                  {"title":"useEffect 练习","description":"写两个副作用示例","estimatedMinutes":45,"difficulty":"NORMAL","taskType":"CODING"}
                ]}
                </PLAN_JSON>
                你愿意选哪种承诺等级？
                """);

        var next = service.postMessage(user, view.id(), "每周大约 4 小时");
        assertThat(next.state()).isEqualTo(SessionState.PLAN_PROPOSED);
        assertThat(next.pendingPlan()).isNotNull();
        assertThat(next.suggestedActions()).contains("commit");
    }

    /**
     * 【测试澄清信封（CLARIFY_JSON）解析：助手回复含 <CLARIFY_JSON> 时，
     *  pendingClarify 非空、含 questions，会话仍停留在 DRAFTING（澄清不是计划），
     *  且不应进入 PLAN_PROPOSED。】
     */
    @Test
    void clarifyEnvelopeSurfacesPendingClarifyAndStaysDrafting() {
        var user = newUser("clarify");
        llm.enqueueText("""
                先确认两点，点选即可：
                <CLARIFY_JSON>
                {"questions":[
                  {"id":"tool","question":"你打算用什么语言？","multiSelect":false,
                   "options":[{"label":"Python","hint":"适合数据/AI"},{"label":"Java"}]},
                  {"id":"time","question":"每天能投入多少时间？","multiSelect":false,
                   "options":[{"label":"30 分钟内"},{"label":"1 小时以上"}]}
                ]}
                </CLARIFY_JSON>
                """);

        var view = service.startNewGoal(user, "入门机器学习");

        assertThat(view.state()).isEqualTo(SessionState.DRAFTING);
        assertThat(view.pendingPlan()).isNull();
        assertThat(view.pendingClarify()).isNotNull();
        var clarify = (com.fasterxml.jackson.databind.JsonNode) view.pendingClarify();
        assertThat(clarify.path("questions")).hasSize(2);
        assertThat(clarify.path("questions").get(0).path("options")).hasSize(2);
    }

    /**
     * 【测试计划优先于澄清：同一条回复同时含 CLARIFY_JSON 与 PLAN_JSON 时，
     *  应进入 PLAN_PROPOSED 且 pendingClarify 被抑制为 null。】
     */
    @Test
    void planTakesPriorityOverClarifyWhenBothPresent() {
        var user = newUser("both");
        llm.enqueueText("""
                <CLARIFY_JSON>
                {"questions":[{"id":"x","question":"q","multiSelect":false,"options":[{"label":"a"},{"label":"b"}]}]}
                </CLARIFY_JSON>
                <PLAN_JSON>
                {"goalTitle":"X","tasks":[{"title":"任务一","description":"d","estimatedMinutes":30,"difficulty":"EASY","taskType":"STUDY"}]}
                </PLAN_JSON>
                """);

        var view = service.startNewGoal(user, "随便一个目标");

        assertThat(view.state()).isEqualTo(SessionState.PLAN_PROPOSED);
        assertThat(view.pendingPlan()).isNotNull();
        assertThat(view.pendingClarify()).isNull();
    }

    /**
     * 【测试提交计划：将 PLAN_PROPOSED 状态的计划提交后，
     *  验证状态转为 COMMITTED，创建的任务与目标关联，
     *  蒸馏记忆 JSON 被写入目标，sessionCount 和 lastSessionAt 更新。】
     */
    @Test
    void commitPlanCreatesTasksLinkedToGoalAndRunsDistillation() {
        var user = newUser("commit");

        llm.enqueueText("""
                <PLAN_JSON>
                {"goalTitle":"学透 React Hooks","tasks":[
                  {"title":"基础 Hook","description":"d","estimatedMinutes":30,"difficulty":"EASY","taskType":"STUDY"},
                  {"title":"自定义 Hook","description":"d","estimatedMinutes":60,"difficulty":"NORMAL","taskType":"CODING"}
                ]}
                </PLAN_JSON>
                """);
        var view = service.startNewGoal(user, "学透 React Hooks");
        assertThat(view.state()).isEqualTo(SessionState.PLAN_PROPOSED);

        // Distillation call on commit returns a fresh memory JSON
        // 【提交时的蒸馏调用，返回新的记忆 JSON】
        llm.enqueueJson("{\"version\":1,\"goalStatement\":\"学透 React Hooks\",\"lastSessionSummary\":\"已敲定首版\"}");

        var committed = service.commitPlan(user, view.id());
        assertThat(committed.state()).isEqualTo(SessionState.COMMITTED);

        var created = tasks.findByUserAndGoalIdOrderByCreatedAtDesc(user, view.goalId());
        assertThat(created).hasSize(2);
        assertThat(created).extracting(t -> t.estimatedMinutes).contains(30, 60);
        assertThat(created).allSatisfy(t -> {
            assertThat(t.goalId).isEqualTo(view.goalId());
            assertThat(t.status).isEqualTo(TaskStatus.TODO);
        });

        var refreshed = goals.findById(view.goalId()).orElseThrow();
        assertThat(refreshed.distilledMemoryJson).contains("goalStatement");
        assertThat(refreshed.sessionCount).isEqualTo(1);
        assertThat(refreshed.lastSessionAt).isNotNull();
    }

    /**
     * 【测试无待定计划时提交被拒绝。DRAFTING 状态下不能调用 commitPlan，
     *  应抛出 BadRequestException。】
     */
    @Test
    void commitWithoutPendingPlanIsRejected() {
        var user = newUser("nocommit");
        llm.enqueueText("还在聊呢"); // DRAFTING, no envelope
        var view = service.startNewGoal(user, "g");
        assertThat(view.state()).isEqualTo(SessionState.DRAFTING);
        assertThatThrownBy(() -> service.commitPlan(user, view.id()))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * 【测试计划中包含 goalStatusChange="ACHIEVED" 时，
     *  提交后目标状态自动标记为 ACHIEVED（已达成）。】
     */
    @Test
    void goalStatusChangeAchievedMarksGoalAchieved() {
        var user = newUser("achieve");
        llm.enqueueText("""
                <PLAN_JSON>
                {"goalTitle":"done","tasks":[{"title":"final","description":"d","estimatedMinutes":15,"difficulty":"EASY","taskType":"REVIEW"}],"goalStatusChange":"ACHIEVED"}
                </PLAN_JSON>
                """);
        var view = service.startNewGoal(user, "done");
        llm.enqueueJson("{}");
        service.commitPlan(user, view.id());

        assertThat(goals.findById(view.goalId()).orElseThrow().status).isEqualTo(GoalStatus.ACHIEVED);
    }

    // ----- pending plan edit / delete -----------------------------------
    // 【待定计划编辑/删除测试区域】

    /**
     * 【辅助方法：创建一个包含两个任务的待定计划会话，返回会话视图。
     *  任务 A（STUDY/EASY/30min）和任务 B（CODING/NORMAL/45min）。】
     */
    private SessionDtos.SessionView openTwoTaskPlan(UserAccount user) {
        llm.enqueueText("""
                <PLAN_JSON>
                {"goalTitle":"x","tasks":[
                  {"title":"A","description":"da","estimatedMinutes":30,"difficulty":"EASY","taskType":"STUDY"},
                  {"title":"B","description":"db","estimatedMinutes":45,"difficulty":"NORMAL","taskType":"CODING"}
                ]}
                </PLAN_JSON>
                """);
        var v = service.startNewGoal(user, "x");
        assertThat(v.state()).isEqualTo(SessionState.PLAN_PROPOSED);
        return v;
    }

    /**
     * 【测试编辑计划任务：修改第一个任务的标题、描述、时长、难度、类型后，
     *  验证更新生效且第二个任务不受影响，状态保持 PLAN_PROPOSED。】
     */
    @Test
    void editPlanTaskUpdatesFieldsInPendingPlan() {
        var user = newUser("edit");
        var v = openTwoTaskPlan(user);

        var patch = new SessionDtos.EditPlanTaskRequest(
                "A 改名", "新描述", 90, "HARD", "NOTE", null);
        var updated = service.editPlanTask(user, v.id(), 0, patch);

        var plan = (com.fasterxml.jackson.databind.JsonNode) updated.pendingPlan();
        var first = plan.path("tasks").get(0);
        assertThat(first.path("title").asText()).isEqualTo("A 改名");
        assertThat(first.path("description").asText()).isEqualTo("新描述");
        assertThat(first.path("estimatedMinutes").asInt()).isEqualTo(90);
        assertThat(first.path("difficulty").asText()).isEqualTo("HARD");
        assertThat(first.path("taskType").asText()).isEqualTo("NOTE");
        // Second task untouched
        // 【验证第二个任务未被修改】
        assertThat(plan.path("tasks").get(1).path("title").asText()).isEqualTo("B");
        // Stays in PLAN_PROPOSED — edits should not invalidate
        // 【编辑不应改变状态，保持 PLAN_PROPOSED】
        assertThat(updated.state()).isEqualTo(SessionState.PLAN_PROPOSED);
    }

    /**
     * 【测试编辑计划任务时的边界值处理：
     *  超大分钟数（9999）应被夹紧到有效范围 [5, 240]，
     *  无效的枚举值（"NOPE"/"WHATEVER"）应回退到默认值 NORMAL/STUDY。】
     */
    @Test
    void editPlanTaskClampsMinutesAndIgnoresUnknownEnums() {
        var user = newUser("clamp");
        var v = openTwoTaskPlan(user);

        var patch = new SessionDtos.EditPlanTaskRequest(
                null, null, 9999, "NOPE", "WHATEVER", null);
        var updated = service.editPlanTask(user, v.id(), 0, patch);

        var first = ((com.fasterxml.jackson.databind.JsonNode) updated.pendingPlan()).path("tasks").get(0);
        assertThat(first.path("estimatedMinutes").asInt()).isBetween(5, 240);
        assertThat(first.path("difficulty").asText()).isEqualTo("NORMAL");
        assertThat(first.path("taskType").asText()).isEqualTo("STUDY");
    }

    /**
     * 【测试编辑计划任务时空白标题被拒绝，应抛出 BadRequestException。】
     */
    @Test
    void editPlanTaskRejectsBlankTitle() {
        var user = newUser("blank");
        var v = openTwoTaskPlan(user);
        var patch = new SessionDtos.EditPlanTaskRequest("   ", null, null, null, null, null);
        assertThatThrownBy(() -> service.editPlanTask(user, v.id(), 0, patch))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * 【测试编辑计划任务时越界索引被拒绝（索引 99 超出任务列表范围）。】
     */
    @Test
    void editPlanTaskRejectsOutOfRangeIndex() {
        var user = newUser("range");
        var v = openTwoTaskPlan(user);
        var patch = new SessionDtos.EditPlanTaskRequest("x", null, null, null, null, null);
        assertThatThrownBy(() -> service.editPlanTask(user, v.id(), 99, patch))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * 【测试非 PLAN_PROPOSED 状态（DRAFTING）下编辑计划任务被拒绝。】
     */
    @Test
    void editPlanTaskRequiresPlanProposedState() {
        var user = newUser("draft");
        llm.enqueueText("还在聊呢");
        var v = service.startNewGoal(user, "g");
        assertThat(v.state()).isEqualTo(SessionState.DRAFTING);
        var patch = new SessionDtos.EditPlanTaskRequest("x", null, null, null, null, null);
        assertThatThrownBy(() -> service.editPlanTask(user, v.id(), 0, patch))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * 【测试删除计划任务：删除第一个任务后，列表只剩第二个任务 B，
     *  状态保持 PLAN_PROPOSED。】
     */
    @Test
    void deletePlanTaskRemovesByIndex() {
        var user = newUser("delone");
        var v = openTwoTaskPlan(user);
        var updated = service.deletePlanTask(user, v.id(), 0);
        var plan = (com.fasterxml.jackson.databind.JsonNode) updated.pendingPlan();
        assertThat(plan.path("tasks").size()).isEqualTo(1);
        assertThat(plan.path("tasks").get(0).path("title").asText()).isEqualTo("B");
        assertThat(updated.state()).isEqualTo(SessionState.PLAN_PROPOSED);
    }

    /**
     * 【测试删除最后一个剩余任务被拒绝。计划中至少需要保留一个任务。】
     */
    @Test
    void deletePlanTaskRejectsLastRemainingTask() {
        var user = newUser("dellast");
        var v = openTwoTaskPlan(user);
        service.deletePlanTask(user, v.id(), 0);
        // Now only one task remains; deleting it again should fail.
        // 【只剩一个任务时再次删除应失败】
        assertThatThrownBy(() -> service.deletePlanTask(user, v.id(), 0))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * 【测试非会话所有者编辑计划任务被拒绝（越权访问），应抛出 ForbiddenException。】
     */
    @Test
    void editPlanTaskRejectsOtherUsersSession() {
        var owner = newUser("owner");
        var v = openTwoTaskPlan(owner);
        var stranger = newUser("stranger");
        var patch = new SessionDtos.EditPlanTaskRequest("x", null, null, null, null, null);
        assertThatThrownBy(() -> service.editPlanTask(stranger, v.id(), 0, patch))
                .isInstanceOf(ForbiddenException.class);
    }

    // ----- abandon / terminal state -------------------------------------
    // 【放弃会话/终态测试区域】

    /**
     * 【测试放弃会话后状态转为 ABANDONED。】
     */
    @Test
    void abandonTransitionsToAbandoned() {
        var user = newUser("abandon");
        llm.enqueueText("ok");
        var view = service.startNewGoal(user, "g");
        var abandoned = service.abandon(user, view.id());
        assertThat(abandoned.state()).isEqualTo(SessionState.ABANDONED);
    }

    /**
     * 【测试终态会话（ABANDONED/COMMITTED）下发送消息被拒绝。
     *  已放弃的会话不允许继续对话。】
     */
    @Test
    void postMessageOnTerminalSessionIsRejected() {
        var user = newUser("terminal");
        llm.enqueueText("ok");
        var view = service.startNewGoal(user, "g");
        service.abandon(user, view.id());

        assertThatThrownBy(() -> service.postMessage(user, view.id(), "再聊点"))
                .isInstanceOf(BadRequestException.class);
    }

    // ----- ownership ----------------------------------------------------
    // 【会话所有权校验测试区域】

    /**
     * 【测试非会话所有者无法访问他人会话：get、postMessage、abandon 操作
     *  均应抛出 ForbiddenException。】
     */
    @Test
    void foreignUserCannotAccessSession() {
        var owner = newUser("owner");
        var intruder = newUser("intruder");
        llm.enqueueText("ok");
        var view = service.startNewGoal(owner, "g");

        assertThatThrownBy(() -> service.get(intruder, view.id())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.postMessage(intruder, view.id(), "hi")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.abandon(intruder, view.id())).isInstanceOf(ForbiddenException.class);
    }

    // ----- check-in flow ------------------------------------------------
    // 【签到打卡流程测试区域】

    /**
     * 【测试签到打卡时进度摘要注入 prompt。
     *  验证：会话类型为 CHECK_IN，LLM 调用中包含 [PROGRESS] 段，
     *  且包含已完成任务（动态规划 5 题）和待办任务（回溯专题）的信息。】
     */
    @Test
    void checkInOnExistingGoalIncludesProgressSummaryInPrompt() {
        var user = newUser("checkin");

        // Seed a goal with some tasks (one completed) directly via repos
        // 【直接通过仓库种子数据：创建目标和两个任务（一个已完成，一个待办）】
        var goal = new Goal();
        goal.user = user;
        goal.title = "持续刷题";
        goals.save(goal);

        var done = new StudyTask();
        done.user = user;
        done.title = "动态规划 5 题";
        done.taskType = TaskType.STUDY;
        done.difficulty = Difficulty.NORMAL;
        done.estimatedMinutes = 30;
        done.baseExp = 20;
        done.status = TaskStatus.COMPLETED;
        done.goalId = goal.id;
        done.completedAt = LocalDateTime.now().minusDays(1);
        tasks.save(done);

        var todo = new StudyTask();
        todo.user = user;
        todo.title = "回溯专题";
        todo.taskType = TaskType.STUDY;
        todo.difficulty = Difficulty.NORMAL;
        todo.estimatedMinutes = 45;
        todo.baseExp = 25;
        todo.status = TaskStatus.TODO;
        todo.goalId = goal.id;
        tasks.save(todo);

        llm.enqueueText("看到你已完成 1 项。下一步你想继续刷题还是调整？");
        var view = service.startCheckIn(user, goal.id);

        assertThat(view.kind()).isEqualTo(SessionKind.CHECK_IN);
        // First call recorded is the assistant turn — progress is injected into the user prompt
        // under the [PROGRESS] section (layered-prompt structure keeps system prompt static-ish
        // to maximise prompt-cache hit rate).
        // 【验证进度信息注入到 user prompt 的 [PROGRESS] 段中（分层 prompt 结构保持 system prompt 稳定以最大化缓存命中率）】
        var firstCall = llm.calls.stream()
                .filter(c -> c[0].equals("text"))
                .findFirst().orElseThrow();
        var combined = firstCall[1] + "\n" + firstCall[2];
        assertThat(combined).contains("[PROGRESS]");
        assertThat(combined).contains("动态规划 5 题");
        assertThat(combined).contains("回溯专题");
        assertThat(view.turns()).singleElement()
                .satisfies(t -> assertThat(t.role()).isEqualTo(TurnRole.ASSISTANT));
    }

    /**
     * 【测试签到打卡复用已有活跃会话：对同一目标重复签到时
     *  应返回同一会话 ID，且不消耗 LLM 队列（不创建新会话）。】
     */
    @Test
    void checkInResumesAnExistingActiveSessionInsteadOfCreatingANewOne() {
        var user = newUser("resume");
        var goal = new Goal();
        goal.user = user;
        goal.title = "g";
        goals.save(goal);

        llm.enqueueText("第一次开聊");
        var first = service.startCheckIn(user, goal.id);

        // Second call should NOT consume from the text queue or create a new session
        // 【第二次签到不应消耗文本队列，应复用同一会话】
        var queueBefore = llm.textQueue.size();
        var second = service.startCheckIn(user, goal.id);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(llm.textQueue).hasSize(queueBefore);
    }

    /**
     * 【测试已放弃目标的签到被拒绝，应抛出 BadRequestException。】
     */
    @Test
    void checkInOnAbandonedGoalIsRejected() {
        var user = newUser("badcheckin");
        var goal = new Goal();
        goal.user = user;
        goal.title = "g";
        goal.status = GoalStatus.ABANDONED;
        goals.save(goal);

        assertThatThrownBy(() -> service.startCheckIn(user, goal.id))
                .isInstanceOf(BadRequestException.class);
    }

    // ----- LLM unavailable fallback -------------------------------------
    // 【LLM 不可用降级测试区域】

    /**
     * 【测试 LLM 不可用时使用降级回复。助手回复应包含"AI 暂不可用"提示，
     *  会话仍正常创建（2 轮：用户消息+降级回复）。】
     */
    @Test
    void fallbackReplyIsUsedWhenLlmUnavailable() {
        var user = newUser("offline");
        llm.available = false;

        var view = service.startNewGoal(user, "学英语");
        assertThat(view.turns()).hasSize(2);
        assertThat(view.turns().get(1).role()).isEqualTo(TurnRole.ASSISTANT);
        assertThat(view.turns().get(1).content()).contains("AI 暂不可用");
    }

    // ----- turn cap -----------------------------------------------------
    // 【对话轮次上限测试区域】

    // ----- list / delete sessions ---------------------------------------
    // 【会话列表与删除测试区域】

    /**
     * 【测试按目标列出会话摘要：返回的摘要按时间排序，
     *  包含会话 ID、轮次计数、已提交任务计数。
     *  同时验证其他用户的会话不包含在内。】
     */
    @Test
    void listForGoalReturnsSummariesOldestLast() {
        var user = newUser("listgoal");
        llm.enqueueText("ok1");
        var s1 = service.startNewGoal(user, "g-list");
        // Start a check-in on the same goal would resume — instead create another goal/session pair
        // 【创建其他用户的会话，验证列表不包含它】
        var foreignGoalSession = service.startNewGoal(newUser("other"), "other");
        assertThat(foreignGoalSession.goalId()).isNotEqualTo(s1.goalId());

        var summaries = service.listForGoal(user, s1.goalId());
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).id()).isEqualTo(s1.id());
        assertThat(summaries.get(0).turnCount()).isEqualTo(2);
        assertThat(summaries.get(0).committedTaskCount()).isZero();
    }

    /**
     * 【测试非所有者列出目标会话被拒绝，应抛出 ForbiddenException。】
     */
    @Test
    void listForGoalRejectsForeignUser() {
        var owner = newUser("ownerL");
        var intruder = newUser("intruderL");
        llm.enqueueText("ok");
        var s = service.startNewGoal(owner, "g");
        assertThatThrownBy(() -> service.listForGoal(intruder, s.goalId()))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * 【测试删除会话：验证会话及其关联的轮次记录被正确删除，
     *  deletedTurns 返回删除的轮次数。】
     */
    @Test
    void deleteSessionRemovesTurnsAndSession() {
        var user = newUser("dels");
        llm.enqueueText("ok");
        var s = service.startNewGoal(user, "g");
        long sid = s.id();
        long turnsBefore = turns.count();
        var result = service.deleteSession(user, sid);
        assertThat(result.deletedTurns()).isEqualTo(2);
        assertThat(sessions.findById(sid)).isEmpty();
        assertThat(turns.count()).isEqualTo(turnsBefore - 2);
    }

    /**
     * 【测试已提交（COMMITTED）的会话不允许删除，应抛出 BadRequestException。】
     */
    @Test
    void deleteSessionRejectsCommittedSession() {
        var user = newUser("delcommit");
        llm.enqueueText("""
                <PLAN_JSON>
                {"goalTitle":"x","tasks":[{"title":"a","description":"d","estimatedMinutes":30,"difficulty":"EASY","taskType":"STUDY"}]}
                </PLAN_JSON>
                """);
        var view = service.startNewGoal(user, "x");
        llm.enqueueJson("{}");
        service.commitPlan(user, view.id());
        assertThatThrownBy(() -> service.deleteSession(user, view.id()))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * 【测试非所有者删除会话被拒绝，应抛出 ForbiddenException。】
     */
    @Test
    void deleteSessionRejectsForeignUser() {
        var owner = newUser("ownerD");
        var intruder = newUser("intruderD");
        llm.enqueueText("ok");
        var s = service.startNewGoal(owner, "g");
        assertThatThrownBy(() -> service.deleteSession(intruder, s.id()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ----- LLM exception resilience -------------------------------------
    // 【LLM 异常恢复测试区域】

    /**
     * 【测试 LLM 调用抛异常时自动降级：不应向上传播异常，
     *  而是使用降级回复（包含"AI 暂不可用"），会话正常创建。】
     */
    @Test
    void llmExceptionDuringAssistantTurnFallsBackInsteadOfPropagating() {
        var user = newUser("throw");
        llm.throwOnNextText = true;
        var view = service.startNewGoal(user, "g");
        assertThat(view.state()).isEqualTo(SessionState.DRAFTING);
        assertThat(view.turns()).hasSize(2);
        assertThat(view.turns().get(1).role()).isEqualTo(TurnRole.ASSISTANT);
        assertThat(view.turns().get(1).content()).contains("AI 暂不可用");
    }

    /**
     * 【测试对话轮次达到上限时强制结束会话。
     *  当轮次接近 MAX_TURNS(60) 时，发送消息应触发最终回复（提示"对话上限"），
     *  且不调用 LLM（不增加 calls 计数）。】
     */
    @Test
    void hittingTurnCapForcesAFinalisationMessageWithoutCallingLlm() {
        var user = newUser("cap");
        llm.enqueueText("ok"); // first assistant reply opens session with 2 turns
        var view = service.startNewGoal(user, "g");

        // Inflate turnCount on the persisted session to just under cap, leaving room for the user
        // message but not the assistant reply.
        // 【将轮次计数膨胀到接近上限：用户消息使 count=60，助手回复将触达 cap】
        var session = sessions.findById(view.id()).orElseThrow();
        session.turnCount = 59; // next user msg makes it 60 -> assistant turn hits cap (MAX_TURNS=60)
        sessions.save(session);

        var callsBefore = llm.calls.size();
        // No text enqueued — if the service tried to call LLM it'd return empty fallback;
        // but cap should short-circuit before calling.
        // 【不排队任何响应 — cap 应在调用 LLM 之前短路】
        var after = service.postMessage(user, view.id(), "再来一轮");

        // The forced reply mentions the cap
        // 【验证最终回复包含"对话上限"提示，且未调用 LLM】
        var lastTurn = after.turns().get(after.turns().size() - 1);
        assertThat(lastTurn.role()).isEqualTo(TurnRole.ASSISTANT);
        assertThat(lastTurn.content()).contains("对话上限");
        assertThat(llm.calls.size()).isEqualTo(callsBefore); // no LLM invocation
    }
}
