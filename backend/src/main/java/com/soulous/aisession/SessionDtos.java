package com.soulous.aisession;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 【AI 规划会话数据传输对象集合：定义会话模块所有请求/响应的 DTO record】
 *
 * <p>采用 Java record 作为不可变 DTO，用于 Controller 层的请求参数绑定和响应序列化。
 * 包含新建目标、打卡跟进、发送消息、编辑计划任务等请求体，以及会话视图、轮次视图、
 * 会话摘要、删除结果等响应体。</p>
 */
public final class SessionDtos {
    /** 【私有构造函数：工具类禁止实例化】 */
    private SessionDtos() {}

    /**
     * 【新建目标请求：用户提交新学习目标时的请求体】
     *
     * @param goal 【目标描述文本，不能为空】
     */
    public record StartNewGoalRequest(@NotBlank String goal) {}

    /**
     * 【打卡跟进请求：用户对已有目标发起 check-in 时的请求体】
     *
     * @param goalId 【目标 ID】
     */
    public record StartCheckInRequest(Long goalId) {}

    /**
     * 【发送消息请求：用户在会话中发送消息时的请求体】
     *
     * @param content 【消息内容，不能为空】
     */
    public record MessageRequest(@NotBlank String content) {}

    /**
     * 【编辑计划任务请求：用户在计划确认前编辑任务卡片字段时的请求体】
     *
     * <p>所有字段均可选，仅非 null 字段会被更新。</p>
     *
     * @param title            【任务标题】
     * @param description      【任务描述】
     * @param estimatedMinutes 【预估时长（分钟）】
     * @param difficulty       【难度等级：EASY / NORMAL / HARD】
     * @param taskType         【任务类型：STUDY / CODING / NOTE / MEMORY / REVIEW / SIMPLE】
     * @param baseExp          【基础经验值】
     */
    public record EditPlanTaskRequest(
            String title,
            String description,
            Integer estimatedMinutes,
            String difficulty,
            String taskType,
            Integer baseExp
    ) {}

    /**
     * 【重复目标候选：新目标与已有目标可能重复时的提示信息】
     *
     * @param goalId 【已有目标的 ID】
     * @param title  【已有目标的标题】
     * @param reason 【判定为重复的原因说明】
     */
    public record DuplicateCandidate(Long goalId, String title, String reason) {}

    /**
     * 【澄清选项：结构化澄清问题中的单个可选项，展示为可点选的按钮/标签。】
     *
     * @param label 【选项显示文本（用户点选的内容）】
     * @param hint  【可选的补充说明，帮助用户理解该选项的取舍，可为 null】
     */
    public record ClarifyOption(String label, String hint) {}

    /**
     * 【澄清问题：AI 拆解过程中需要用户补充信息时给出的一道结构化选择题。
     * 前端据此渲染选项卡片，用户点选后把选择回灌成一条消息继续对话，
     * 替代以往纯文字一问一答的体验。】
     *
     * @param id          【问题标识（如 "tool"、"time"），用于前端去重和拼装回灌文本】
     * @param question    【问题文本】
     * @param multiSelect 【是否允许多选】
     * @param options     【可选项列表】
     */
    public record ClarifyQuestion(String id, String question, boolean multiSelect, List<ClarifyOption> options) {}

    /**
     * 【会话视图：返回给前端的完整会话快照，包含所有对话轮次和计划草案】
     *
     * @param id                   【会话 ID】
     * @param goalId               【关联目标 ID】
     * @param goalTitle            【关联目标标题】
     * @param kind                 【会话类型：NEW_GOAL / CHECK_IN】
     * @param state                【当前会话状态】
     * @param turnCount            【已产生的对话轮次总数】
     * @param pendingPlan          【待确认计划的 JSON 对象（已解析），未提出时为 null】
     * @param pendingClarify       【待回答的结构化澄清问题 JSON 对象（形如 {"questions":[...]}），无则为 null。
     *                              已提出计划（PLAN_PROPOSED）时一律为 null——计划优先于澄清。】
     * @param turns                【全部对话轮次视图列表】
     * @param suggestedActions     【当前状态下建议的用户操作列表】
     * @param duplicateCandidates  【与已有目标的重复候选列表，仅新建目标时非空】
     * @param distillationWarning  【蒸馏警告信息，当长期记忆因超限未能更新时显示】
     */
    public record SessionView(
            Long id,
            Long goalId,
            String goalTitle,
            SessionKind kind,
            SessionState state,
            int turnCount,
            Object pendingPlan,
            Object pendingClarify,
            List<TurnView> turns,
            List<String> suggestedActions,
            List<DuplicateCandidate> duplicateCandidates,
            /**
             * 【蒸馏警告：当最近一次蒸馏输出超过 4KB 无法持久化时设置为非 null 的用户提示】
             *
             * <p>Set to a non-null user-facing message when the last distillation pass produced
             * output too large to persist (>4KB). Indicates that the goal's long-term memory
             * snapshot did NOT include the latest session's content — the prior memory was
             * kept verbatim. Frontend surfaces this so the user knows the conversation was
             * too dense to summarise rather than thinking everything was captured.</p>
             */
            String distillationWarning
    ) {}

    /**
     * 【轮次视图：单条对话轮次的前端展示数据】
     *
     * @param id      【轮次 ID】
     * @param idx     【轮次序号】
     * @param role    【发言角色：USER / ASSISTANT / SYSTEM】
     * @param content 【发言内容文本】
     */
    public record TurnView(Long id, int idx, TurnRole role, String content) {}

    /**
     * 【会话摘要：会话列表页展示的精简会话信息】
     *
     * @param id                【会话 ID】
     * @param goalId            【关联目标 ID】
     * @param kind              【会话类型】
     * @param state             【会话状态】
     * @param turnCount         【对话轮次数】
     * @param startedAt         【开始时间】
     * @param lastActivityAt    【最后活跃时间】
     * @param endedAt           【结束时间，未结束时为 null】
     * @param committedTaskCount【已确认的任务数量】
     */
    public record SessionSummary(
            Long id,
            Long goalId,
            SessionKind kind,
            SessionState state,
            int turnCount,
            LocalDateTime startedAt,
            LocalDateTime lastActivityAt,
            LocalDateTime endedAt,
            int committedTaskCount
    ) {}

    /**
     * 【删除会话结果：删除操作的返回信息】
     *
     * @param id           【被删除的会话 ID】
     * @param deletedTurns 【同时删除的对话轮次数】
     */
    public record DeleteSessionResult(long id, int deletedTurns) {}
}
