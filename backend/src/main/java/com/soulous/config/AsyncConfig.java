package com.soulous.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 【异步任务线程池配置 —— 为 {@code @Async} 风格的异步工作定义独立的线程池。
 * 每种工作负载使用独立的线程池 Bean，防止某个失控的任务（如 AI 审核）
 * 阻塞或耗尽其他异步任务（如通知推送、邮件发送）的线程资源。
 * 这种隔离策略提高了系统在高负载下的稳定性和可预测性。】
 *
 * <p>Thread-pool definitions for {@code @Async}-style work. Keep these explicit (one
 * bean per workload) so a runaway AI review can't choke unrelated async work like
 * notification fan-out or email sinks.</p>
 */
@Configuration
public class AsyncConfig {
    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * 【AI 审核处理器专用线程池 —— 服务于 {@code AiReviewProcessor.runReview}。
     *
     * <p>运行模式切换：
     * <ul>
     *   <li>生产环境：使用真正的线程池（2 核心线程 / 8 最大线程 / 50 队列容量），
     *       足以支撑少量并发提交者；当线程池饱和时拒绝新任务，提交状态保持为
     *       {@code AI_REVIEWING} 并在 UI 上展示，优于无界队列导致的 OOM 风险。</li>
     *   <li>测试/调试环境：通过配置 {@code soulous.async.ai-review.sync=true}
     *       切换为 {@link SyncTaskExecutor}，使审核任务在调用线程同步执行，
     *       避免断言与工作线程之间的竞态条件。</li>
     * </ul></p>
     *
     * <p>Executor for {@code AiReviewProcessor.runReview}. Real pool in production;
     * {@link SyncTaskExecutor} in tests so assertions on the post-submit state don't
     * race the worker thread. Flipping is via {@code soulous.async.ai-review.sync}.</p>
     *
     * <p>Real pool sizing: 2 core / 8 max / queue 50 — enough for a handful of
     * concurrent submitters. Saturation rejects further work; the submission stays
     * in {@code AI_REVIEWING} which the UI surfaces — better than unbounded-queue
     * OOM under attack.</p>
     *
     * @param sync 【是否使用同步执行器，由配置项 soulous.async.ai-review.sync 控制，默认 false】
     * @return 【任务执行器实例——同步执行器或线程池执行器】
     */
    @Bean(name = "aiReviewExecutor")
    public TaskExecutor aiReviewExecutor(@Value("${soulous.async.ai-review.sync:false}") boolean sync) {
        if (sync) {
            log.info("aiReviewExecutor: SYNC (tests / debug) — reviews run on the caller thread");
            return new SyncTaskExecutor();
        }
        var ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("ai-review-");
        ex.setKeepAliveSeconds(60);
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }
}
