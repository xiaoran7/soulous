package com.soulous;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulous.ai.LlmService;
import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.chat.ChatDtos;
import com.soulous.chat.ChatRole;
import com.soulous.chat.ChatService;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.task.TaskRepository;
import com.soulous.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【ChatService 集成测试：覆盖分类 CRUD、对话创建/移动/删除、发消息与 PLAN_JSON 抽取、
 *  计划落地为无目标任务、自动命名、越权校验。使用 H2 内存库 + 脚本化 LLM 桩。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-service-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
@Import(ChatServiceTests.TestLlmConfig.class)
class ChatServiceTests {

    @TestConfiguration
    static class TestLlmConfig {
        @Bean @Primary
        ScriptedLlm scriptedLlm() { return new ScriptedLlm(); }
    }

    /** 【脚本化 LLM 桩：complete() 按 FIFO 返回预设文本，completeJson() 按 FIFO 返回预设 JSON】 */
    static class ScriptedLlm extends LlmService {
        private final ObjectMapper mapper = new ObjectMapper();
        final Deque<String> textQueue = new ArrayDeque<>();
        final Deque<String> jsonQueue = new ArrayDeque<>();
        boolean available = true;

        ScriptedLlm() { super("openai", "stub-key", "stub-model", "", 30, false, 16, 60); }

        @Override public boolean isAvailable() { return available; }
        @Override public boolean supportsStreaming() { return false; }

        @Override
        public Optional<String> complete(String system, String user) {
            if (!available) return Optional.empty();
            return Optional.ofNullable(textQueue.pollFirst());
        }

        // completeJsonValidated(...) 的便捷重载内部走这个方法，补救式信封重抽据此可在测试里被脚本化。
        @Override
        public Optional<JsonNode> completeJson(String system, String user) {
            if (!available) return Optional.empty();
            var raw = jsonQueue.pollFirst();
            if (raw == null) return Optional.empty();
            try { return Optional.of(mapper.readTree(raw)); }
            catch (Exception ex) { return Optional.empty(); }
        }

        void enqueueText(String s) { textQueue.add(s); }
        void enqueueJson(String s) { jsonQueue.add(s); }
        void reset() { textQueue.clear(); jsonQueue.clear(); available = true; }
    }

    @Autowired ScriptedLlm llm;
    @Autowired ChatService service;
    @Autowired UserService users;
    @Autowired TaskRepository tasks;

    @BeforeEach
    void resetLlm() { llm.reset(); }

    private UserAccount newUser(String tag) {
        var unique = tag + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", tag, unique + "@example.com"));
        return users.byToken(auth.token());
    }

    // ----- categories + tree -----

    @Test
    void createCategoryAppearsInTree() {
        var user = newUser("cat");
        var cat = service.createCategory(user, "考研");
        assertThat(cat.id()).isNotNull();
        var tree = service.tree(user);
        assertThat(tree.categories()).extracting(ChatDtos.CategoryView::name).contains("考研");
    }

    @Test
    void deleteCategoryUnbindsItsConversations() {
        var user = newUser("delcat");
        var cat = service.createCategory(user, "日语");
        var conv = service.createConversation(user, cat.id());
        assertThat(conv.categoryId()).isEqualTo(cat.id());

        service.deleteCategory(user, cat.id());

        var refreshed = service.getConversation(user, conv.id());
        assertThat(refreshed.categoryId()).isNull(); // 解绑到默认组，对话本身保留
        assertThat(service.tree(user).categories()).isEmpty();
    }

    // ----- conversations -----

    @Test
    void createConversationDefaultsToUncategorized() {
        var user = newUser("conv");
        var conv = service.createConversation(user, null);
        assertThat(conv.categoryId()).isNull();
        assertThat(conv.title()).isEqualTo("新对话");
        assertThat(conv.messages()).isEmpty();
    }

    @Test
    void moveConversationBetweenCategories() {
        var user = newUser("move");
        var a = service.createCategory(user, "A");
        var b = service.createCategory(user, "B");
        var conv = service.createConversation(user, a.id());

        var moved = service.updateConversation(user, conv.id(),
                new ChatDtos.UpdateConversationRequest(null, b.id(), null));
        assertThat(moved.categoryId()).isEqualTo(b.id());

        var cleared = service.updateConversation(user, conv.id(),
                new ChatDtos.UpdateConversationRequest(null, null, true));
        assertThat(cleared.categoryId()).isNull();
    }

    // ----- messaging + plan -----

    @Test
    void postMessageAutoTitlesAndAppendsAssistantReply() {
        var user = newUser("msg");
        var conv = service.createConversation(user, null);
        llm.enqueueText("好的，我来帮你。");

        var view = service.postMessage(user, conv.id(), "帮我规划 Spring Boot 学习");

        assertThat(view.messages()).hasSize(2);
        assertThat(view.messages().get(0).role()).isEqualTo(ChatRole.USER);
        assertThat(view.messages().get(1).role()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(view.title()).isEqualTo("帮我规划 Spring Boot 学习");
    }

    @Test
    void planEnvelopeSurfacesPendingPlanAndCommitCreatesGoallessTasks() {
        var user = newUser("plan");
        var conv = service.createConversation(user, null);
        llm.enqueueText("""
                这是计划：
                <PLAN_JSON>
                {"tasks":[
                  {"title":"读 useState 文档","description":"读官方文档","estimatedMinutes":30,"difficulty":"EASY","taskType":"NOTE"},
                  {"title":"useEffect 练习","description":"写两个示例","estimatedMinutes":45,"difficulty":"NORMAL","taskType":"CODING"}
                ]}
                </PLAN_JSON>
                """);

        var view = service.postMessage(user, conv.id(), "把它拆成任务");
        assertThat(view.pendingPlan()).isNotNull();
        assertThat(view.suggestedActions()).contains("commit");

        var committed = service.commitPlan(user, conv.id());
        assertThat(committed.pendingPlan()).isNull();

        var created = tasks.findByUserOrderByCreatedAtDesc(user);
        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(t -> {
            assertThat(t.goalId).isNull();              // 聊天落地的任务不挂目标
            assertThat(t.status).isEqualTo(TaskStatus.TODO);
        });
        assertThat(created).extracting(t -> t.estimatedMinutes).contains(30, 45);
    }

    @Test
    void planCategoryFromEnvelopeMaterializesOntoTasksForUncategorizedConversation() {
        // 痛点1：未分类「新对话」里拆出来的任务，应自动继承 AI 在计划信封里起好的大类，
        // 不再落地为 category=null 让用户手动补。
        var user = newUser("plancat");
        var conv = service.createConversation(user, null); // 未分类
        llm.enqueueText("""
                给你安排好了：
                <PLAN_JSON>
                {"category":"数据结构","tasks":[
                  {"title":"指针与引用","description":"复习指针","estimatedMinutes":30,"difficulty":"EASY","taskType":"STUDY"},
                  {"title":"链表实现","description":"手写单链表","estimatedMinutes":45,"difficulty":"NORMAL","taskType":"CODING"}
                ]}
                </PLAN_JSON>
                """);

        var view = service.postMessage(user, conv.id(), "把 C++ 数据结构拆成任务");
        assertThat(view.pendingPlan()).isNotNull();

        service.commitPlan(user, conv.id());
        var created = tasks.findByUserOrderByCreatedAtDesc(user);
        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(t -> assertThat(t.category).isEqualTo("数据结构"));
    }

    @Test
    void claimedPlanWithoutEnvelopeIsRecoveredByRepairPass() {
        // 痛点2：模型只在文字里声称「已生成计划草案」却没输出 <PLAN_JSON> 信封时，
        // harness 应二次调用把描述转成结构化计划，让计划卡片仍能出现。
        var user = newUser("repair");
        var conv = service.createConversation(user, null);
        // 主回复：有计划声称、无信封
        llm.enqueueText("好的，已为你定制 4 周学习计划（共 2 个任务）。（已生成计划草案↓）");
        // 补救调用返回的结构化 JSON
        llm.enqueueJson("""
                {"category":"C++ 入门","tasks":[
                  {"title":"指针与 STL","description":"打基础","estimatedMinutes":40,"difficulty":"EASY","taskType":"STUDY"},
                  {"title":"树与图","description":"进阶结构","estimatedMinutes":60,"difficulty":"NORMAL","taskType":"STUDY"}
                ]}
                """);

        var view = service.postMessage(user, conv.id(), "直接给我计划");
        assertThat(view.pendingPlan()).isNotNull();
        assertThat(view.suggestedActions()).contains("commit");

        service.commitPlan(user, conv.id());
        var created = tasks.findByUserOrderByCreatedAtDesc(user);
        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(t -> assertThat(t.category).isEqualTo("C++ 入门"));
    }

    @Test
    void plainAnswerWithoutPlanClaimDoesNotTriggerRepair() {
        // 补救只针对「声称给了计划」的回复，普通答疑不应误触发二次调用。
        var user = newUser("noplan");
        var conv = service.createConversation(user, null);
        llm.enqueueText("二叉树是一种每个节点最多两个子节点的数据结构。");
        llm.enqueueJson("""
                {"category":"误触发","tasks":[{"title":"X","description":"d","estimatedMinutes":30,"difficulty":"EASY","taskType":"STUDY"}]}
                """);

        var view = service.postMessage(user, conv.id(), "什么是二叉树");
        assertThat(view.pendingPlan()).isNull();
        assertThat(llm.jsonQueue).hasSize(1); // 补救未被调用，JSON 仍在队列里
    }

    @Test
    void newUserMessageInvalidatesStalePendingPlan() {
        var user = newUser("stale");
        var conv = service.createConversation(user, null);
        llm.enqueueText("""
                <PLAN_JSON>
                {"tasks":[{"title":"a","description":"d","estimatedMinutes":30,"difficulty":"EASY","taskType":"STUDY"}]}
                </PLAN_JSON>
                """);
        var first = service.postMessage(user, conv.id(), "给我计划");
        assertThat(first.pendingPlan()).isNotNull();

        llm.enqueueText("好的，我们再聊聊细节。");
        var second = service.postMessage(user, conv.id(), "等等，我想调整");
        assertThat(second.pendingPlan()).isNull();
        assertThatThrownBy(() -> service.commitPlan(user, conv.id()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void fallbackReplyWhenLlmUnavailable() {
        var user = newUser("offline");
        var conv = service.createConversation(user, null);
        llm.available = false;

        var view = service.postMessage(user, conv.id(), "在吗");
        assertThat(view.messages()).hasSize(2);
        assertThat(view.messages().get(1).content()).contains("AI 暂不可用");
    }

    // ----- ownership -----

    @Test
    void foreignUserCannotAccessConversation() {
        var owner = newUser("owner");
        var intruder = newUser("intruder");
        var conv = service.createConversation(owner, null);

        assertThatThrownBy(() -> service.getConversation(intruder, conv.id()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.postMessage(intruder, conv.id(), "hi"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.deleteConversation(intruder, conv.id()))
                .isInstanceOf(ForbiddenException.class);
    }
}
