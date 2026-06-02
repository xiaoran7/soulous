package com.soulous.chat;

/**
 * 【聊天消息角色】USER = 用户发言，ASSISTANT = AI 回复。
 * 新的「AI 拆解」对话模块独立于旧的 aisession.TurnRole，避免跨模块耦合。
 */
public enum ChatRole {
    USER,
    ASSISTANT
}
