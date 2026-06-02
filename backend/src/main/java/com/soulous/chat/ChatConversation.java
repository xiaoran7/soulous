package com.soulous.chat;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.time.LocalDateTime;

/**
 * 【聊天对话】一段连续的 AI 拆解对话（取代旧的「目标 + 会话」二级结构）。
 *
 * <p>对话可选择性归入某个 {@link ChatCategory}（category 为 null 表示未分类/默认组）。
 * 通过 runningSummary 滚动摘要控制 prompt 体积；pendingPlanJson 非空表示 AI 已提出
 * 可落地为任务的计划草案（等价于旧的 PLAN_PROPOSED 状态）。</p>
 */
@Entity
public class ChatConversation {
    /** 【主键 ID，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属用户】 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【所属分类，可空（未分类 → 前端「默认」组）】 */
    @ManyToOne
    public ChatCategory category;

    /** 【对话标题，首条用户消息后自动命名】 */
    @Column(nullable = false, length = 200)
    public String title = "新对话";

    /** 【待确认计划 JSON：AI 提出的 PLAN_JSON 草案原文，非空即「计划待确认」】 */
    @Column(columnDefinition = "TEXT")
    public String pendingPlanJson;

    /** 【滚动摘要：超出最近窗口的早期对话被 LLM 压缩后的文本】 */
    @Column(columnDefinition = "TEXT")
    public String runningSummary;

    /** 【摘要进度上界（独占）：idx 小于此值的消息已折叠进 runningSummary】 */
    public int summarizedUpToIdx = 0;

    /** 【消息计数器，用作下一条消息的 idx】 */
    public int turnCount = 0;

    /** 【创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
    /** 【最后活跃时间：每次发消息更新，用于对话列表排序】 */
    public LocalDateTime lastActivityAt = LocalDateTime.now();
}
