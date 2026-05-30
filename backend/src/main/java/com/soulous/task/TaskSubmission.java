package com.soulous.task;

import com.soulous.auth.UserAccount;
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
 * 【任务提交记录实体：存储用户对学习任务的提交证据，包括文字证明、截图、代码片段、
 * 链接等多种形式。每次提交都会经过 AI 审核流程，状态由 {@link SubmissionStatus} 管理。
 * 与 {@link StudyTask} 是多对一关系——一个任务可以有多次提交记录。】
 */
@Entity
public class TaskSubmission {
    /** 【主键 ID，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    /** 【关联的学习任务，不可为空】 */
    @ManyToOne(optional = false)
    public StudyTask task;
    /** 【提交用户，不可为空】 */
    @ManyToOne(optional = false)
    public UserAccount user;
    /** 【文字证明：用户对学习过程的文字描述，TEXT 类型支持长文本】 */
    @Column(columnDefinition = "TEXT")
    public String textProof;
    /** 【单张截图 URL（兼容旧版本，新版本使用 screenshotUrls）】 */
    public String screenshotUrl;
    /** 【多张截图 URL，以逗号分隔存储，TEXT 类型】 */
    @Column(columnDefinition = "TEXT")
    public String screenshotUrls;
    /** 【代码片段：用于编程类任务的代码提交证明】 */
    @Column(columnDefinition = "TEXT")
    public String codeSnippet;
    /** 【证明链接：外部资源链接，如 GitHub 仓库、在线笔记等】 */
    public String proofLink;
    /** 【学习时长（分钟），默认为 0，由计时器功能记录或用户手动输入】 */
    public Integer studyMinutes = 0;
    /** 【提交类型标识，由 detectSubmitType 自动生成，如 "TEXT,TIMER,SCREENSHOT" 等组合】 */
    public String submitType;
    /** 【提交状态，默认 PENDING，提交后由 TaskService.submit 设为 AI_REVIEWING】 */
    @Enumerated(EnumType.STRING)
    public SubmissionStatus status = SubmissionStatus.PENDING;
    /** 【管理员评语：管理员进行人工审核时填写的备注，TEXT 类型】 */
    @Column(columnDefinition = "TEXT")
    public String adminComment;
    /** 【内容安全审核拦截原因：当触发敏感词过滤时记录具体原因】 */
    @Column(columnDefinition = "TEXT")
    public String moderationReason;
    /** 【记录创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
    /** 【记录最后更新时间，每次状态变更时更新】 */
    public LocalDateTime updatedAt = LocalDateTime.now();
}
