package com.soulous.aisession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 【会话清理定时任务：定期清除长时间未活跃的草稿态规划会话及其关联的对话轮次】
 *
 * <p>通过 {@code @Scheduled} 定时触发，默认每天凌晨 4 点执行。
 * 清理目标为处于 DRAFTING 或 PLAN_PROPOSED 状态且超过 TTL 天数未活跃的会话，
 * 防止僵尸会话占用数据库存储。TTL 天数和 cron 表达式均可通过配置项覆盖。</p>
 */
@Component
public class SessionCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(SessionCleanupTask.class);

    private final PlanningSessionRepository sessions;
    private final SessionTurnRepository turns;
    /** 【草稿态会话的存活天数上限：超过此天数未活跃的会话将被清理，默认 7 天】 */
    private final int draftingTtlDays;

    /**
     * 【构造函数：注入仓储依赖并读取 TTL 配置】
     *
     * @param sessions        【规划会话仓储】
     * @param turns           【会话轮次仓储】
     * @param draftingTtlDays 【草稿存活天数，来自配置项 soulous.session.drafting-ttl-days，默认 7】
     */
    public SessionCleanupTask(PlanningSessionRepository sessions,
                              SessionTurnRepository turns,
                              @Value("${soulous.session.drafting-ttl-days:7}") int draftingTtlDays) {
        this.sessions = sessions;
        this.turns = turns;
        this.draftingTtlDays = Math.max(1, draftingTtlDays);
    }

    /** 【待清理的会话状态列表：仅清理 DRAFTING 和 PLAN_PROPOSED 两种中间态】 */
    private static final List<SessionState> STALE_STATES = List.of(
            SessionState.DRAFTING, SessionState.PLAN_PROPOSED);

    /**
     * 【清除过期草稿会话：定时扫描并删除超时未活跃的草稿态会话及其全部轮次】
     *
     * <p>默认每天凌晨 4:00 执行（cron: 0 0 4 * * *），可通过 soulous.session.cleanup-cron 配置覆盖。</p>
     */
    @Scheduled(cron = "${soulous.session.cleanup-cron:0 0 4 * * *}")
    @Transactional
    public void purgeStaleDrafts() {
        var cutoff = LocalDateTime.now().minusDays(draftingTtlDays);
        var stale = sessions.findByStateInAndLastActivityAtBefore(STALE_STATES, cutoff);
        if (stale.isEmpty()) return;
        int turnsDeleted = 0;
        for (var s : stale) {
            var sturns = turns.findBySessionOrderByIdxAsc(s);
            turnsDeleted += sturns.size();
            turns.deleteBySession(s);
            sessions.delete(s);
        }
        log.info("SessionCleanup purged {} stale planning sessions (>{} days idle, states={}), {} turns",
                stale.size(), draftingTtlDays, STALE_STATES, turnsDeleted);
    }
}
