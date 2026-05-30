package com.soulous;

import com.soulous.ai.LlmService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【LLM 服务测试类：验证 LlmService 的缓存机制（TTL、命中/未命中、清除）、
 * mock provider 直通、API key 缺失时不可用、失败计数与错误记录、
 * 失败不缓存（后续成功可正常返回）、completeJson 的 Markdown 围栏剥离与解析、
 * 以及 info 接口的 provider/availability 报告。
 * 使用 TestableLlm 子类短路 HTTP 调用并控制时钟，确保测试离线且确定性运行。】
 */
class LlmServiceTests {

    /**
     * 【可测试 LLM 子类：短路 HTTP 调用，通过 responder 函数驱动响应，
     * 通过 clock 原子变量控制时间推进，支持缓存 TTL 测试。
     *
     * <p>Subclass that short-circuits HTTP and lets tests drive responses + the clock.</p>
     */
    static class TestableLlm extends LlmService {
        final AtomicInteger invokeCount = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        volatile java.util.function.BiFunction<String, String, String> responder =
                (s, u) -> "ok:" + u;

        TestableLlm(boolean cacheEnabled, long ttlSeconds) {
            super("openai", "test-key", "test-model", "", 30, cacheEnabled, 256, ttlSeconds);
        }

        @Override
        public String invoke(String provider, String system, String user) throws Exception {
            invokeCount.incrementAndGet();
            return responder.apply(system, user);
        }

        // completeJson now routes through the JSON-mode dispatch hook; drive it from the same
        // responder so JSON-path tests stay HTTP-free.
        @Override
        public String invokeJson(String provider, String system, String user) throws Exception {
            invokeCount.incrementAndGet();
            return responder.apply(system, user);
        }

        @Override
        public long now() {
            return clock.get();
        }
    }

    /**
     * 【测试场景：mock provider 下调用 complete 应返回空 Optional，
     * 且不触碰任何遥测计数器（totalCalls 和 failures 均为 0）。】
     */
    @Test
    void mockProviderReturnsEmptyAndDoesNotTouchTelemetry() {
        var llm = new LlmService("mock", "", "", "", 30, true, 256, 300);
        var result = llm.complete("sys", "hi");
        assertThat(result).isEmpty();
        assertThat(llm.stats().get("totalCalls")).isEqualTo(0L);
        assertThat(llm.stats().get("failures")).isEqualTo(0L);
    }

    /**
     * 【测试场景：API key 为空时 provider 应报告不可用（isAvailable=false），
     * 调用 complete 应返回空 Optional。】
     */
    @Test
    void missingApiKeyMakesProviderUnavailable() {
        var llm = new LlmService("openai", "", "gpt", "", 30, true, 256, 300);
        assertThat(llm.isAvailable()).isFalse();
        assertThat(llm.complete("s", "u")).isEmpty();
    }

    /**
     * 【测试场景：启用缓存时，相同 (system, user) 的第二次调用应命中缓存，
     * invokeCount 仅增加 1 次（首次），totalCalls 为 2，cacheHits 为 1，
     * successes 为 1，cacheSize 为 1。】
     */
    @Test
    void cacheReturnsSameValueOnSecondCall() {
        var llm = new TestableLlm(true, 300);
        var first = llm.complete("sys", "user-1");
        var second = llm.complete("sys", "user-1");

        assertThat(first).contains("ok:user-1");
        assertThat(second).contains("ok:user-1");
        assertThat(llm.invokeCount.get()).isEqualTo(1);

        var stats = llm.stats();
        assertThat(stats.get("totalCalls")).isEqualTo(2L);
        assertThat(stats.get("cacheHits")).isEqualTo(1L);
        assertThat(stats.get("successes")).isEqualTo(1L);
        assertThat(stats.get("cacheSize")).isEqualTo(1);
    }

    /**
     * 【测试场景：不同的 prompt 应被分别缓存，不会互相覆盖，
     * invokeCount 为 2（两次不同调用），cacheHits 为 1（第三次命中第一次的缓存），
     * cacheSize 为 2。】
     */
    @Test
    void differentPromptsAreCachedSeparately() {
        var llm = new TestableLlm(true, 300);
        llm.complete("sys", "a");
        llm.complete("sys", "b");
        llm.complete("sys", "a");

        assertThat(llm.invokeCount.get()).isEqualTo(2);
        assertThat(llm.stats().get("cacheHits")).isEqualTo(1L);
        assertThat(llm.stats().get("cacheSize")).isEqualTo(2);
    }

    /**
     * 【测试场景：缓存 TTL 过期后，相同 prompt 应重新调用 LLM（缓存失效），
     * 设置 TTL=1 秒，推进时钟 2 秒后再次调用，invokeCount 应为 2，
     * cacheHits 应为 0。】
     */
    @Test
    void cacheRespectsTtl() {
        var llm = new TestableLlm(true, 1); // 1 second
        llm.complete("sys", "x");
        assertThat(llm.invokeCount.get()).isEqualTo(1);

        llm.clock.addAndGet(2_000); // advance past TTL
        llm.complete("sys", "x");

        assertThat(llm.invokeCount.get()).isEqualTo(2);
        assertThat(llm.stats().get("cacheHits")).isEqualTo(0L);
    }

    /**
     * 【测试场景：缓存禁用时，每次调用都应触发实际的 LLM invoke，
     * 三次相同 prompt 调用后 invokeCount 为 3，cacheHits 为 0，cacheSize 为 0。】
     */
    @Test
    void disabledCacheInvokesEveryTime() {
        var llm = new TestableLlm(false, 300);
        llm.complete("sys", "x");
        llm.complete("sys", "x");
        llm.complete("sys", "x");

        assertThat(llm.invokeCount.get()).isEqualTo(3);
        assertThat(llm.stats().get("cacheHits")).isEqualTo(0L);
        assertThat(llm.stats().get("cacheSize")).isEqualTo(0);
    }

    /**
     * 【测试场景：LLM 调用抛出异常时，失败计数器应递增，
     * lastFailureProvider 和 lastFailureMessage 应记录最后一次失败的详细信息，
     * lastFailureAt 应记录失败发生的时钟时间，successes 保持为 0。】
     */
    @Test
    void failuresIncrementCounterAndRecordLastError() {
        var llm = new TestableLlm(true, 300);
        llm.responder = (s, u) -> { throw new RuntimeException("boom: rate limited"); };

        var result = llm.complete("sys", "x");

        assertThat(result).isEmpty();
        var stats = llm.stats();
        assertThat(stats.get("failures")).isEqualTo(1L);
        assertThat(stats.get("successes")).isEqualTo(0L);
        assertThat(stats.get("lastFailureProvider")).isEqualTo("openai");
        assertThat(stats.get("lastFailureMessage").toString()).contains("boom");
        assertThat(stats.get("lastFailureAt")).isEqualTo(llm.clock.get());
    }

    /**
     * 【测试场景：失败调用不应被缓存，后续成功调用应正常返回并计入 successes，
     * 确保瞬时故障恢复后服务能自动恢复工作。】
     */
    @Test
    void failuresAreNotCachedSoLaterSuccessGoesThrough() {
        var llm = new TestableLlm(true, 300);
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        llm.responder = (s, u) -> {
            if (fail.get()) throw new RuntimeException("transient");
            return "recovered";
        };

        assertThat(llm.complete("sys", "x")).isEmpty();
        fail.set(false);
        assertThat(llm.complete("sys", "x")).contains("recovered");

        assertThat(llm.invokeCount.get()).isEqualTo(2);
        assertThat(llm.stats().get("failures")).isEqualTo(1L);
        assertThat(llm.stats().get("successes")).isEqualTo(1L);
    }

    /**
     * 【测试场景：调用 clearCache 后，缓存应被清空，
     * 再次调用相同 prompt 应触发新的 LLM invoke（invokeCount=2）。】
     */
    @Test
    void clearCacheForcesRefetch() {
        var llm = new TestableLlm(true, 300);
        llm.complete("sys", "x");
        llm.clearCache();
        llm.complete("sys", "x");
        assertThat(llm.invokeCount.get()).isEqualTo(2);
    }

    /**
     * 【测试场景：completeJson 应自动剥离 LLM 返回的 Markdown 代码围栏（如 ```json ... ```），
     * 正确解析为 JsonNode，验证 score 和 ok 字段值。】
     */
    @Test
    void completeJsonStripsMarkdownFencesAndParses() {
        var llm = new TestableLlm(true, 300);
        llm.responder = (s, u) -> "```json\n{\"score\": 42, \"ok\": true}\n```";

        var node = llm.completeJson("sys", "rate this").orElseThrow();

        assertThat(node.get("score").asInt()).isEqualTo(42);
        assertThat(node.get("ok").asBoolean()).isTrue();
    }

    /**
     * 【测试场景：当 LLM 返回无法解析为 JSON 的内容时，completeJson 应返回空 Optional。】
     */
    @Test
    void completeJsonReturnsEmptyOnUnparseableOutput() {
        var llm = new TestableLlm(true, 300);
        llm.responder = (s, u) -> "no json here at all";
        assertThat(llm.completeJson("sys", "x")).isEmpty();
    }

    /**
     * 【测试场景：completeJsonValidated 在首次输出不合格时，带错误回灌重试一次后成功。
     * 首次返回非 JSON，重试（user 含"上一次回答"标记）返回合格 JSON，invokeCount 应为 2。】
     */
    @Test
    void completeJsonValidatedRetriesOnceThenSucceeds() {
        var llm = new TestableLlm(false, 300);
        llm.responder = (s, u) -> u.contains("上一次回答") ? "{\"ok\":true}" : "not json at all";
        var node = llm.completeJsonValidated("sys", "do it", j -> j.path("ok").asBoolean(false));
        assertThat(node).isPresent();
        assertThat(node.get().get("ok").asBoolean()).isTrue();
        assertThat(llm.invokeCount.get()).isEqualTo(2);
    }

    /**
     * 【测试场景：completeJsonValidated 两次都不合格时放弃，返回空 Optional，invokeCount 为 2。】
     */
    @Test
    void completeJsonValidatedGivesUpAfterTwoFailures() {
        var llm = new TestableLlm(false, 300);
        llm.responder = (s, u) -> "still not valid json";
        var node = llm.completeJsonValidated("sys", "do it", j -> j.path("ok").asBoolean(false));
        assertThat(node).isEmpty();
        assertThat(llm.invokeCount.get()).isEqualTo(2);
    }

    /**
     * 【测试场景：info 接口应正确报告 provider 名称、可用状态和模型名称。】
     */
    @Test
    void infoReportsProviderAndAvailability() {
        var llm = new LlmService("openai", "k", "gpt-x", "", 30, true, 256, 300);
        var info = llm.info();
        assertThat(info.get("provider")).isEqualTo("openai");
        assertThat(info.get("available")).isEqualTo("true");
        assertThat(info.get("model")).isEqualTo("gpt-x");
    }
}
