package com.soulous.chat;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 【聊天对话仓储】 */
public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {
    /** 【按用户查全部对话，按最后活跃时间降序（最近的在前）】 */
    List<ChatConversation> findByUserOrderByLastActivityAtDesc(UserAccount user);

    /** 【某分类下的全部对话（删除分类时把它们解绑到默认组）】 */
    List<ChatConversation> findByCategory(ChatCategory category);

    /** 【按分类批量解绑：删除分类前把其下对话的 category 置空】 */
    @Modifying
    @Transactional
    void deleteByCategory(ChatCategory category);
}
