package com.soulous.task;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 【学习记录实体类——映射到数据库的 study_record 表。
 *  记录用户每次学习的时长、日期和学习摘要。
 *  与 UserAccount 是多对一关系（一个用户可有多条学习记录），
 *  与 StudyTask 是多对一关系（一个任务可对应多条学习记录，task 可为 null）。
 *  用于生成学习统计图表和成就系统的数据来源。】
 *
 * <p>English: JPA entity representing a study record — tracks the duration,
 * date, and summary of a single study session.</p>
 */
@Entity
public class StudyRecord {

    /** 【主键 ID，自增策略由数据库自动生成】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【关联的用户账号，不允许为空。多条学习记录属于同一个用户】 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【关联的学习任务，允许为空。一条学习记录可以独立存在，也可以关联到具体任务】 */
    @ManyToOne
    public StudyTask task;

    /** 【本次学习时长（单位：分钟）】 */
    public Integer studyMinutes;

    /** 【学习日期，默认为当天。用于按日统计学习数据】 */
    public LocalDate recordDate = LocalDate.now();

    /** 【学习摘要——以 TEXT 类型存储，用户对本次学习内容的总结或备注】 */
    @Column(columnDefinition = "TEXT")
    public String summary;

    /** 【记录创建时间，默认为当前时间。用于时间范围查询和排序】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
