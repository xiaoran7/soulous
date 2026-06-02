package com.soulous.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 【聊天消息仓储】 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    /** 【按对话查全部消息（idx 升序）】 */
    List<ChatMessage> findByConversationOrderByIdxAsc(ChatConversation conversation);

    /** 【最近 12 条（idx 降序）：滚动摘要与最近窗口用】 */
    List<ChatMessage> findTop12ByConversationOrderByIdxDesc(ChatConversation conversation);

    /** 【按对话批量删除：删除对话时清空其消息】 */
    @Modifying
    @Transactional
    void deleteByConversation(ChatConversation conversation);
}
