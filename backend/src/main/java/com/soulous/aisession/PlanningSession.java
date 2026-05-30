package com.soulous.aisession;

import com.soulous.auth.UserAccount;
import com.soulous.goal.Goal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;

import java.time.LocalDateTime;

/**
 * 【规划会话实体：承载一次完整的 AI 规划对话，关联用户和目标】
 *
 * <p>每条记录代表一次用户与 AI 学习教练之间的对话会话。会话有明确的生命周期
 * （DRAFTING → PLAN_PROPOSED → COMMITTED / ABANDONED / CLOSED）。
 * 通过 runningSummary 机制实现滚动摘要，避免超长对话导致 prompt 膨胀。</p>
 */
@Entity
public class PlanningSession {
    /** 【主键 ID，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属用户：多对一关联到 UserAccount，标识会话的所有者】 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【关联目标：多对一关联到 Goal，标识本次会话围绕哪个学习目标展开】 */
    @ManyToOne(optional = false)
    public Goal goal;

    /** 【会话类型：NEW_GOAL（新建目标）或 CHECK_IN（打卡跟进），以字符串持久化】 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public SessionKind kind;

    /** 【会话状态：当前所处的生命周期阶段，默认为 DRAFTING（起草中）】 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    public SessionState state = SessionState.DRAFTING;

    /** 【对话轮次计数器：记录已产生的对话轮数，用于判断是否达到上限】 */
    public int turnCount = 0;

    /** 【待确认计划 JSON：AI 提出的 PLAN_JSON 草案原文，用户确认后转化为实际任务】 */
    @Column(columnDefinition = "TEXT")
    public String pendingPlanJson;

    /** 【已确认任务 ID 列表 JSON：用户确认计划后生成的任务 ID 数组，用于追溯会话产出】 */
    @Column(columnDefinition = "TEXT")
    public String committedTaskIdsJson;

    /** 【会话开始时间】 */
    public LocalDateTime startedAt = LocalDateTime.now();
    /** 【最后活跃时间：每次对话轮次更新，用于清理过期会话的判断依据】 */
    public LocalDateTime lastActivityAt = LocalDateTime.now();
    /** 【会话结束时间：仅在 COMMITTED / ABANDONED / CLOSED 时设置】 */
    public LocalDateTime endedAt;

    /**
     * 【滚动摘要：对超出最近窗口的早期对话轮次进行 LLM 压缩后的摘要文本，增量重建】
     *
     * <p>English: Compressed summary of turns older than the recent window. Rebuilt incrementally.</p>
     */
    @Column(columnDefinition = "TEXT")
    public String runningSummary;

    /**
     * 【摘要进度上界（独占）：idx 小于此值的轮次已被折叠进 runningSummary，不再重复处理】
     *
     * <p>English: Exclusive upper bound — turns with idx &lt; this have been folded into runningSummary.</p>
     */
    public int summarizedUpToIdx = 0;

    /**
     * 【蒸馏警告：当长期记忆 JSON 超过 4KB 安全阈值时由 closeWithDistillation 临时设置】
     *
     * <p>Set transiently by {@code closeWithDistillation} when the distilled JSON exceeds the
     * 4KB safety cap and the prior memory had to be kept instead. Surfaced through SessionView
     * so the user knows their long conversation was too dense to summarise — without this
     * the only signal was a backend warn log.</p>
     */
    @Transient
    public String distillationWarning;
}
