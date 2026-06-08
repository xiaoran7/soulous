package com.soulous.companion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 【陪伴宠物聊天的请求/响应 DTO】 */
public final class CompanionDtos {
    private CompanionDtos() {}

    /** 【发给宠物的一句话】 */
    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {}

    /** 【宠物的回复】 */
    public record ChatReply(String reply) {}

    /** 【一条聊天消息：role = user(用户) / pet(宠物)】 */
    public record ChatMessage(String role, String text) {}

    /** 【聊天历史】 */
    public record History(List<ChatMessage> messages) {}

    /** 【宠物记得的一条事实：category = 类别(如 兴趣/目标)，text = 事实文本】 */
    public record MemoryFact(String category, String text) {}

    /** 【宠物的记忆（结构化画像事实），用于侧边栏展示「它记得你什么」】 */
    public record Memory(List<MemoryFact> facts) {}
}
