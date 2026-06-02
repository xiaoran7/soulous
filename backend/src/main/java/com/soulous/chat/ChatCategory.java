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
 * 【聊天分类】用户自建的对话文件夹（类似 Gemini 的分组）。
 * 未归类的对话不属于任何分类，在前端归入「默认」组。
 */
@Entity
public class ChatCategory {
    /** 【主键 ID，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属用户】 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【分类名称】 */
    @Column(nullable = false, length = 60)
    public String name;

    /** 【排序权重，越小越靠前；预留给后续手动排序】 */
    public int sortOrder = 0;

    /** 【创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
