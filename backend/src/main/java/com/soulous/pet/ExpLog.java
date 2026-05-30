package com.soulous.pet;

import com.soulous.auth.UserAccount;
import com.soulous.task.StudyTask;
import com.soulous.task.TaskSubmission;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;

/**
 * 【经验变动日志实体：记录宠物每次经验值变动的详细信息。
 *  用于审计追踪经验值来源，前端展示经验变动历史。
 *  每条日志关联一个用户，可选关联任务和提交记录。】
 */
@Entity
public class ExpLog {
    /** 【主键 ID，自增生成】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    /** 【关联的用户账户，多对一关系，不可为空】 */
    @ManyToOne(optional = false)
    public UserAccount user;
    /** 【关联的学习任务，可为空（如专注完成奖励不关联任务）】 */
    @ManyToOne
    public StudyTask task;
    /** 【关联的任务提交记录，可为空（如任务开始事件不关联提交）】 */
    @ManyToOne
    public TaskSubmission submission;
    /** 【本次经验值变动数量，正数表示获得，0 表示仅状态变更】 */
    public Integer expAmount;
    /** 【事件类型：EXP_GAINED（任务奖励）、FOCUS_EXP（专注奖励）、TASK_STARTED、
     *  SUBMITTED_FOR_REVIEW、NEEDS_MORE、REJECTED 等】 */
    public String eventType;
    /** 【变动原因的中文描述，供前端展示】 */
    public String reason;
    /** 【日志创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
