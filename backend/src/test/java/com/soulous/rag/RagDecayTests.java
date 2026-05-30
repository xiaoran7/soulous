package com.soulous.rag;

import org.junit.jupiter.api.Test;
import org.assertj.core.data.Offset;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the pure decay math — exercised directly against the package-private
 * {@link RetrievalService#timeDecay} to avoid spinning up Spring just for arithmetic.
 *
 * 【RetrievalService.timeDecay() 时间衰减函数的纯数学单元测试。
 *  直接调用包私有方法，无需启动 Spring 上下文。
 *  覆盖场景：
 *  1. 零年龄时权重为 1.0（完整权重）
 *  2. 半衰期时权重恰好为 0.5
 *  3. 两个半衰期时权重为 0.25
 *  4. 半衰期 <= 0 时衰减禁用（始终返回 1.0）
 *  5. 近期低相似度记忆可胜过旧的高相似度记忆（衰减的实际价值）】
 */
class RagDecayTests {

    /**
     * 【测试零年龄（刚创建的记忆）权重为 1.0（完整权重）。】
     */
    @Test
    void zeroAgeIsFullWeight() {
        var now = LocalDateTime.of(2026, 5, 18, 12, 0);
        assertThat(RetrievalService.timeDecay(now, now, 90)).isEqualTo(1.0);
    }

    /**
     * 【测试半衰期（90 天）时权重恰好为 0.5，允许 0.001 误差。】
     */
    @Test
    void halfLifeIsExactlyHalfWeight() {
        var now = LocalDateTime.of(2026, 5, 18, 12, 0);
        assertThat(RetrievalService.timeDecay(now.minusDays(90), now, 90))
                .isCloseTo(0.5, Offset.offset(0.001));
    }

    /**
     * 【测试两个半衰期（180 天）时权重为 0.25（0.5²），允许 0.001 误差。】
     */
    @Test
    void twoHalfLivesIsQuarterWeight() {
        var now = LocalDateTime.of(2026, 5, 18, 12, 0);
        assertThat(RetrievalService.timeDecay(now.minusDays(180), now, 90))
                .isCloseTo(0.25, Offset.offset(0.001));
    }

    /**
     * 【测试半衰期 <= 0 时衰减禁用：无论年龄多长，权重始终为 1.0。】
     */
    @Test
    void disabledWhenHalfLifeLeqZero() {
        var now = LocalDateTime.of(2026, 5, 18, 12, 0);
        assertThat(RetrievalService.timeDecay(now.minusYears(5), now, 0)).isEqualTo(1.0);
        assertThat(RetrievalService.timeDecay(now.minusYears(5), now, -1)).isEqualTo(1.0);
    }

    /**
     * 【测试衰减的实际价值：近期低相似度记忆可胜过旧的高相似度记忆。
     *  具体场景：300 天前的 0.85 相似度记忆 vs 7 天前的 0.70 相似度记忆，
     *  经过 90 天半衰期衰减后，近期记忆的加权分数更高。
     *  这证明了时间衰减在用户面向排名中的必要性。】
     */
    @Test
    void recentMemoryWithLowerSimilarityCanOutrankOldHighSimilarity() {
        // Concrete demo of why we want decay: a 300-day-old 0.85 sim memory shouldn't
        // beat a 7-day-old 0.70 sim memory in user-facing ranking.
        // 【具体演示为什么需要衰减：300 天前的 0.85 相似度记忆不应胜过 7 天前的 0.70 相似度记忆】
        var now = LocalDateTime.of(2026, 5, 18, 12, 0);
        var oldScore = 0.85 * RetrievalService.timeDecay(now.minusDays(300), now, 90);
        var recentScore = 0.70 * RetrievalService.timeDecay(now.minusDays(7), now, 90);
        assertThat(recentScore).isGreaterThan(oldScore);
    }
}
