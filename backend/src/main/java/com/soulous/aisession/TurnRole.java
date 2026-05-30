package com.soulous.aisession;

/**
 * 【对话角色枚举：标识每一轮对话的发言者身份】
 *
 * <p>USER — 用户发起的消息；ASSISTANT — AI 助手的回复；SYSTEM — 系统注入的上下文或提示。</p>
 */
public enum TurnRole {
    /** 【用户角色：由终端用户手动输入的消息】 */
    USER,
    /** 【助手角色：由 LLM 生成的回复内容】 */
    ASSISTANT,
    /** 【系统角色：由系统自动注入的上下文信息或提示语】 */
    SYSTEM
}
