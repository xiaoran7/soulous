package com.soulous.common.health;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 【指标初始化器：在应用启动完成时（ApplicationReadyEvent）预注册自定义计数器，
 * 将其初始值设为零。这样即使尚无任何流量，Prometheus 抓取时也能看到这些指标，
 * 避免仪表盘在健康空闲服务上显示"无数据"而造成误导。
 *
 * 设计要点：
 * - 仅预注册无标签或已知标签组合的计数器（低基数维度）
 * - 高基数标签维度（如 LLM provider/model、限流规则名、审核结论）采用懒加载方式，
 *   在首次事件到达时自动创建】
 *
 * <p>Pre-registers our custom counters at zero so they appear in the Prometheus scrape
 * even before any traffic has incremented them. Without this, scrapers can't graph
 * "events per second" until the first event arrives — and dashboards show "no data"
 * for healthy idle services, which is misleading.</p>
 *
 * <p>We only pre-register the tag-less (or known-tag) counters; counters with high-
 * cardinality tags (LLM provider/model, rate-limit rule name, moderation verdict) get
 * created lazily as their first event happens.</p>
 */
@Component
public class MetricsInitializer {
    /** 【Micrometer 指标注册中心】 */
    private final MeterRegistry registry;

    /**
     * 【构造函数：注入 Micrometer 指标注册中心】
     *
     * @param registry 【Micrometer MeterRegistry 实例】
     */
    public MetricsInitializer(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 【应用就绪事件监听器：为每个指标族引导一个代表性系列，
     * 使仪表盘有基线数据，抓取测试可在不触发真实流量的情况下断言指标名称存在。】
     *
     * <p>Bootstrap a representative series for each family so dashboards have a baseline
     * and scrape tests can assert on the metric name without first triggering traffic.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void prime() {
        // Bootstrap a representative series for each family so dashboards have a baseline
        // and scrape tests can assert on the metric name without first triggering traffic.
        /** 【预注册 LLM 调用计数器，使用 unknown 作为供应商/模型/结果的基线值】 */
        registry.counter("soulous.llm.calls.total",
                "provider", "unknown", "model", "unknown", "outcome", "success");
        /** 【预注册限流拦截计数器，使用 unknown 作为规则名的基线值】 */
        registry.counter("soulous.rate_limit.blocked.total", "rule", "unknown");
        /** 【预注册内容审核计数器，默认以 PASS/INPUT 作为基线值】 */
        registry.counter("soulous.moderation.verdict.total", "verdict", "PASS", "target", "INPUT");
        /** 【预注册存储 GC 删除计数器（无标签）】 */
        registry.counter("soulous.storage.gc.deleted.total");
        /** 【预注册刷新令牌重放计数器（无标签）】 */
        registry.counter("soulous.refresh_token.replayed.total");
        /** 【预注册推送通知计数器，使用 unknown 作为通知类型的基线值】 */
        registry.counter("soulous.notification.pushed.total", "type", "unknown");
    }
}
