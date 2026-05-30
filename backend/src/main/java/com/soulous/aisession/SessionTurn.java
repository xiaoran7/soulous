package com.soulous.aisession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.time.LocalDateTime;

/**
 * 【会话轮次实体：记录规划会话中每一轮对话的发言内容】
 *
 * <p>每个 PlanningSession 由多条 SessionTurn 按 idx 顺序组成完整的对话历史。
 * 角色分为 USER（用户）、ASSISTANT（AI 助手）和 SYSTEM（系统注入）。</p>
 */
@Entity
public class SessionTurn {
    /** 【主键 ID，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属会话：多对一关联到 PlanningSession】 */
    @ManyToOne(optional = false)
    public PlanningSession session;

    /** 【轮次序号：从 0 开始递增，用于维护对话的先后顺序】 */
    @Column(nullable = false)
    public int idx;

    /** 【发言角色：USER / ASSISTANT / SYSTEM，以字符串形式持久化】 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public TurnRole role;

    /** 【发言内容：对话的正文文本，以 TEXT 类型存储大文本】 */
    @Column(columnDefinition = "TEXT")
    public String content;

    /** 【创建时间：该轮对话的生成时间戳，默认取当前时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
