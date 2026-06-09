package com.soulous.timetable;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.time.LocalDateTime;

/**
 * 【课程成绩实体：代表教务系统一门课程的最终成绩记录。
 *  数据来源是同步课表时由爬虫一并抓取（{@code --mode all} 的「成绩」段），
 *  成绩查询天然跨学期，每条记录自带「开课学期」，按 semester 分学期组织。
 *  与 {@link CourseEntry} 风格一致：public 字段 + @ManyToOne user，不写 getter/setter。
 *  学分/绩点等保留教务系统原始字符串（可能为空或非数值，如「优秀」），由前端按需解析。】
 */
@Entity
public class GradeEntry {
    /** 【主键 ID，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属用户，不可为空】 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【开课学期，如 "2025-2026-2"。成绩接口每条记录自带】 */
    @Column(length = 40)
    public String semester;

    /** 【课程编号】 */
    @Column(length = 64)
    public String courseCode;

    /** 【课程名称】 */
    @Column(length = 200)
    public String courseName;

    /** 【开课单位/学院】 */
    @Column(length = 120)
    public String department;

    /** 【成绩原文，如 "88" / "优秀" / "及格"】 */
    @Column(length = 40)
    public String score;

    /** 【成绩标识，如 "正常" / "缓考"】 */
    @Column(length = 40)
    public String scoreFlag;

    /** 【学分原文】 */
    @Column(length = 16)
    public String credit;

    /** 【绩点原文】 */
    @Column(length = 16)
    public String gpa;

    /** 【总学时原文】 */
    @Column(length = 16)
    public String totalHours;

    /** 【考核方式，如 "考试" / "考查"】 */
    @Column(length = 40)
    public String assessMethod;

    /** 【考试性质，如 "初修" / "补考" / "重修"】 */
    @Column(length = 40)
    public String examNature;

    /** 【课程属性，如 "必修" / "选修"】 */
    @Column(length = 40)
    public String courseAttr;

    /** 【课程性质，如 "公共基础课"】 */
    @Column(length = 40)
    public String courseNature;

    /** 【创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
