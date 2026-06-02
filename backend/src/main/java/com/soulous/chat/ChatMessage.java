package com.soulous.chat;

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
 * 【聊天消息】对话中的一条发言，按 idx 顺序组成完整历史。
 *
 * <p>content 用 MEDIUMTEXT：上传的 md/pdf/txt 附件文本（上限 3 万字符）会拼进用户消息，
 * 单条可能超过 MySQL TEXT 的 ~64KB 上限。</p>
 */
@Entity
public class ChatMessage {
    /** 【主键 ID，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属对话】 */
    @ManyToOne(optional = false)
    public ChatConversation conversation;

    /** 【消息序号：从 0 开始递增】 */
    @Column(nullable = false)
    public int idx;

    /** 【发言角色：USER / ASSISTANT】 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public ChatRole role;

    /** 【发言内容】 */
    @Column(columnDefinition = "MEDIUMTEXT")
    public String content;

    /** 【创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
