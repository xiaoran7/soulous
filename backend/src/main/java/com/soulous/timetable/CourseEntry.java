package com.soulous.timetable;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/**
 * 【课程表条目实体：代表用户课表里的一节课（某天某节次的一门课程）。
 *  数据来源是用户从教务系统粘贴的课表 HTML，经大模型解析为结构化字段后落库。
 *  一个用户可以有多条 CourseEntry，按 (semester, dayOfWeek, startSection) 组织成周课表。
 *  以后移动端登录教务系统同步时，写入的也是同一张表，前端无需改动即可展示。】
 *
 * <p>设计与 {@link com.soulous.task.StudyTask}/{@code Goal} 一致：public 字段 + @ManyToOne user，
 * 不写 getter/setter，保持仓库内实体风格统一。</p>
 */
@Entity
public class CourseEntry {
    /** 【主键 ID，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属用户，不可为空，多对一关系】 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【课程名称，必填，最长 200 字符】 */
    @NotBlank
    @Column(nullable = false, length = 200)
    public String courseName;

    /** 【任课教师，可空】 */
    @Column(length = 100)
    public String teacher;

    /** 【上课地点（教学楼/教室），可空】 */
    @Column(length = 120)
    public String location;

    /** 【星期几：1=周一 … 7=周日。教务课表统一以周一为一周起点】 */
    @Column(nullable = false)
    public int dayOfWeek;

    /** 【起始节次（第几节开始），如 1。可空——若 HTML 只给了时间没有节次】 */
    public Integer startSection;

    /** 【结束节次（第几节结束），如 2。可空】 */
    public Integer endSection;

    /** 【上课开始时间 HH:mm（如 "08:00"），LLM 能从 HTML 抽到就存，便于网格精确渲染。可空】 */
    @Column(length = 8)
    public String startTime;

    /** 【上课结束时间 HH:mm（如 "09:40"）。可空】 */
    @Column(length = 8)
    public String endTime;

    /** 【开课周次原文，如 "1-16" / "1-8,10-16" / "3-15"。保留原始字符串，不强行解析。可空】 */
    @Column(length = 60)
    public String weeks;

    /** 【单双周，默认 ALL（每周都上）。LLM 识别到"单/双周"时填 ODD/EVEN】 */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    public WeekParity weekParity = WeekParity.ALL;

    /** 【所属学期，如 "2025-2026-2"。支持同一用户保存/切换多个学期的课表。可空】 */
    @Column(length = 40)
    public String semester;

    /** 【创建时间，实体实例化时自动设置】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
