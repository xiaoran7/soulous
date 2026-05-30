package com.soulous.appeal;

import com.soulous.auth.UserAccount;
import com.soulous.task.TaskSubmission;
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
 * 【申诉记录实体类，映射到数据库 appeal 表。
 * 记录用户对任务提交结果的申诉信息，包括申诉原因、截图证据、
 * 审核状态、管理员审核意见及审核时间等。
 * 与 TaskSubmission（任务提交）和 UserAccount（用户账号）存在多对一关联。】
 */
@Entity
public class Appeal {
    /** 【申诉记录唯一标识，自增主键】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【关联的任务提交记录，多对一关系，不可为空】 */
    @ManyToOne(optional = false)
    public TaskSubmission submission;

    /** 【发起申诉的用户，多对一关系，不可为空】 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【用户填写的申诉原因，TEXT 类型支持长文本】 */
    @Column(columnDefinition = "TEXT")
    public String appealReason;

    /** 【截图证据URL列表，多个URL以逗号分隔存储，TEXT 类型】 */
    @Column(columnDefinition = "TEXT")
    public String screenshotUrls;

    /** 【申诉状态枚举，默认为 PENDING（待审核）】 */
    @Enumerated(EnumType.STRING)
    public AppealStatus status = AppealStatus.PENDING;

    /** 【处理该申诉的管理员ID】 */
    public Long adminId;

    /** 【管理员审核意见/评论，TEXT 类型】 */
    @Column(columnDefinition = "TEXT")
    public String adminComment;

    /** 【申诉创建时间，默认为当前时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();

    /** 【管理员审核时间，审核完成后设置】 */
    public LocalDateTime reviewedAt;
}
