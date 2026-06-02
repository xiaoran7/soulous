package com.soulous.chat;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 【聊天模块 DTO 集合】请求/响应的不可变 record。
 */
public final class ChatDtos {
    private ChatDtos() {}

    // ----- requests -----

    /** 【创建/重命名分类请求】 */
    public record CategoryRequest(@NotBlank String name) {}

    /** 【创建对话请求：可选归入某分类】 */
    public record CreateConversationRequest(Long categoryId) {}

    /** 【更新对话请求：重命名 和/或 移动到某分类。categoryId 传 null + clearCategory=true 表示移出分类。】 */
    public record UpdateConversationRequest(String title, Long categoryId, Boolean clearCategory) {}

    /** 【发送消息请求】 */
    public record MessageRequest(@NotBlank String content) {}

    /** 【编辑计划草案任务请求：仅非 null 字段生效】 */
    public record EditPlanTaskRequest(
            String title,
            String description,
            Integer estimatedMinutes,
            String difficulty,
            String taskType,
            Integer baseExp
    ) {}

    // ----- responses -----

    /** 【分类视图】 */
    public record CategoryView(Long id, String name) {}

    /** 【对话摘要：侧边栏列表用】 */
    public record ConversationSummary(
            Long id, String title, Long categoryId,
            LocalDateTime lastActivityAt, int messageCount) {}

    /** 【侧边栏树：分类列表 + 全部对话摘要（categoryId 为 null 即默认组）】 */
    public record TreeView(List<CategoryView> categories, List<ConversationSummary> conversations) {}

    /** 【单条消息视图】 */
    public record MessageView(Long id, int idx, ChatRole role, String content) {}

    /**
     * 【对话完整视图】
     * @param pendingPlan    待确认计划 JSON（已解析），无则 null
     * @param pendingClarify 待回答的结构化澄清问题 JSON，无则 null（计划待确认时一律 null）
     * @param suggestedActions 当前可用操作标识（commit/adjust 等）
     */
    public record ConversationView(
            Long id,
            String title,
            Long categoryId,
            List<MessageView> messages,
            Object pendingPlan,
            Object pendingClarify,
            List<String> suggestedActions
    ) {}

    /** 【删除结果】 */
    public record DeleteResult(long id) {}
}
