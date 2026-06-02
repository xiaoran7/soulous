package com.soulous.chat;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 【聊天分类仓储】 */
public interface ChatCategoryRepository extends JpaRepository<ChatCategory, Long> {
    /** 【按用户查全部分类，按排序权重再按创建时间升序】 */
    List<ChatCategory> findByUserOrderBySortOrderAscCreatedAtAsc(UserAccount user);
}
