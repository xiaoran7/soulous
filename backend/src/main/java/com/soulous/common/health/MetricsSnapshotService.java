package com.soulous.common.health;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 【指标快照服务：从 Micrometer 注册中心读取各业务计数器，将其转换为前端友好的数据结构。
 * 本类是 {@link MetricsInitializer} 的配套组件——MetricsInitializer 负责在流量到达前预注册计数器，
 * 本类负责在运行时聚合这些计数器并输出快照。
 *
 * 设计上有意不做通用的 /metrics 全量导出，避免管理面板被 jvm.* / system.* 等底层指标淹没。
 * 新增业务信号时需在此处显式添加对应字段。】
 *
 * <p>Reads the Micrometer registry and projects the counters we care about into a
 * frontend-friendly shape. Companion to {@link MetricsInitializer} (which primes
 * the same counter families so they exist before any traffic arrives).</p>
 *
 * <p>This is deliberately not a generic /metrics dump — we don't want the admin
 * panel to drown in jvm.* / system.* meters. Add fields explicitly when surfacing
 * new business signals.</p>
 */
@Service
public class MetricsSnapshotService {
    /** 【Micrometer 指标注册中心，所有计数器的存储后端】 */
    private final MeterRegistry registry;

    /**
     * 【构造函数：注入 Micrometer 指标注册中心】
     *
     * @param registry 【Micrometer MeterRegistry 实例】
     */
    public MetricsSnapshotService(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 【生成当前时刻的指标快照。聚合以下业务维度：
     * 1. LLM 调用统计（总次数、成功/失败次数、按供应商分组）
     * 2. 限流拦截统计（按规则名称分组）
     * 3. 内容审核统计（按审核结论分组）
     * 4. 存储 GC 删除计数
     * 5. 刷新令牌重放计数
     * 6. 推送通知统计（按类型分组）
     * 同时记录应用运行时长（秒）。】
     *
     * @return 【包含所有聚合指标的 Snapshot 记录对象】
     */
    public Snapshot snapshot() {
        var llmCounters = registry.find("soulous.llm.calls.total").counters();
        long llmSuccess = sumByTag(llmCounters, "outcome", "success");
        long llmFailure = sumByTag(llmCounters, "outcome", "failure");
        var llmByProvider = groupByTag(llmCounters, "provider");

        var rateLimitByRule = groupByTag(
                registry.find("soulous.rate_limit.blocked.total").counters(), "rule");
        var moderationByVerdict = groupByTag(
                registry.find("soulous.moderation.verdict.total").counters(), "verdict");
        var notificationsByType = groupByTag(
                registry.find("soulous.notification.pushed.total").counters(), "type");

        long storageGcDeleted = sumAll(registry.find("soulous.storage.gc.deleted.total").counters());
        long refreshTokenReplayed = sumAll(registry.find("soulous.refresh_token.replayed.total").counters());

        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;

        return new Snapshot(
                uptimeSeconds,
                new LlmSection(llmSuccess + llmFailure, llmSuccess, llmFailure, llmByProvider),
                rateLimitByRule,
                moderationByVerdict,
                storageGcDeleted,
                refreshTokenReplayed,
                notificationsByType
        );
    }

    /**
     * 【对所有计数器求和，忽略标签维度。用于 storage.gc.deleted.total、
     * refresh_token.replayed.total 等无标签或单标签计数器的聚合。】
     *
     * @param counters 【可迭代的计数器集合】
     * @return 【所有计数器数值的总和】
     */
    private static long sumAll(Iterable<Counter> counters) {
        long sum = 0;
        for (var c : counters) sum += (long) c.count();
        return sum;
    }

    /**
     * 【按指定标签键值对筛选计数器并求和。例如按 outcome="success" 筛选 LLM 调用成功次数。】
     *
     * @param counters 【可迭代的计数器集合】
     * @param tagKey   【标签键名，如 "outcome"、"provider"】
     * @param tagValue 【标签值，如 "success"、"failure"】
     * @return 【匹配指定标签值的所有计数器数值之和】
     */
    private static long sumByTag(Iterable<Counter> counters, String tagKey, String tagValue) {
        long sum = 0;
        for (var c : counters) {
            if (tagValue.equals(c.getId().getTag(tagKey))) sum += (long) c.count();
        }
        return sum;
    }

    /**
     * 【按指定标签键对计数器进行分组聚合，并过滤掉合成的 "unknown" 基线条目。
     * 当存在真实数据时，"unknown" 基线（由 MetricsInitializer 预注册）会被隐藏，
     * 因为它不代表真实事件来源，显示出来会误导用户。
     * 仅当 "unknown" 是唯一条目（即尚无真实流量）时才保留，确保面板仍有数据显示。】
     *
     * <p>Aggregate counts by one tag, drop the synthetic "unknown" baseline entries when real data exists.</p>
     *
     * @param counters 【可迭代的计数器集合】
     * @param tagKey   【用于分组的标签键名】
     * @return 【按计数降序排列的 TagCount 列表】
     */
    private static List<TagCount> groupByTag(Iterable<Counter> counters, String tagKey) {
        Map<String, Long> agg = new TreeMap<>();
        for (var c : counters) {
            String key = c.getId().getTag(tagKey);
            if (key == null) key = "unknown";
            agg.merge(key, (long) c.count(), Long::sum);
        }
        // Hide the baseline "unknown" stub the MetricsInitializer pre-registers — it would
        // confuse users since it's not a real event source. Keep it only if it is the
        // sole entry (i.e. no real traffic yet) so the panel still has a row to show.
        if (agg.size() > 1) agg.remove("unknown");
        return agg.entrySet().stream()
                .map(e -> new TagCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(TagCount::count).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 【指标快照数据结构，包含应用运行时长及各业务维度的聚合指标。
     * 使用 Java record 定义，天然不可变且自带 equals/hashCode/toString。】
     *
     * @param uptimeSeconds           【应用自启动以来的运行时长（秒）】
     * @param llm                     【LLM 调用统计详情】
     * @param rateLimitBlockedByRule  【限流拦截统计，按规则名称分组】
     * @param moderationByVerdict     【内容审核统计，按审核结论分组】
     * @param storageGcDeleted        【存储 GC 累计删除的对象数】
     * @param refreshTokenReplayed    【刷新令牌累计重放次数】
     * @param notificationsPushedByType 【推送通知统计，按通知类型分组】
     */
    public record Snapshot(
            long uptimeSeconds,
            LlmSection llm,
            List<TagCount> rateLimitBlockedByRule,
            List<TagCount> moderationByVerdict,
            long storageGcDeleted,
            long refreshTokenReplayed,
            List<TagCount> notificationsPushedByType
    ) {}

    /**
     * 【LLM 调用统计详情，包含总调用次数、成功次数、失败次数及按供应商分组的明细。】
     *
     * @param total      【LLM 调用总次数（success + failure）】
     * @param success    【成功调用次数】
     * @param failure    【失败调用次数】
     * @param byProvider 【按供应商名称分组的调用次数列表】
     */
    public record LlmSection(long total, long success, long failure, List<TagCount> byProvider) {}

    /**
     * 【标签计数记录，用于表示某个标签维度下的聚合计数值。
     * 例如 label="openai", count=120 表示 OpenAI 供应商的调用次数为 120。】
     *
     * @param label 【标签名称（如供应商名、规则名、审核结论等）】
     * @param count 【该标签对应的累计计数值】
     */
    public record TagCount(String label, long count) {}
}
