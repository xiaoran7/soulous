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
 * 【考试安排实体：代表教务系统某学期的一场考试安排。
 *  数据来源是同步课表时由爬虫一并抓取（{@code --mode all} 的「考试安排」段），
 *  按 (semester) 分学期组织。考试安排本身不带学期字段，由后端用同步的学期标识打上。
 *  与 {@link CourseEntry} 风格一致：public 字段 + @ManyToOne user，不写 getter/setter。】
 */
@Entity
public class ExamEntry {
    /** 【主键 ID，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属用户，不可为空】 */
    @ManyToOne(optional = false)
    public UserAccount user;

    /** 【所属学期，如 "2025-2026-2"。考试接口不返回学期，由同步时的学期标识填入】 */
    @Column(length = 40)
    public String semester;

    /** 【课程名称】 */
    @Column(length = 200)
    public String courseName;

    /** 【课程编号】 */
    @Column(length = 64)
    public String courseCode;

    /** 【授课教师】 */
    @Column(length = 100)
    public String teacher;

    /** 【考试时间原文，如 "2026-01-05 09:00~11:00"】 */
    @Column(length = 120)
    public String examTime;

    /** 【考场/教室】 */
    @Column(length = 120)
    public String room;

    /** 【考试校区】 */
    @Column(length = 80)
    public String campus;

    /** 【座位号】 */
    @Column(length = 40)
    public String seatNo;

    /** 【考试场次，如 "第1场"】 */
    @Column(name = "exam_session", length = 80)
    public String session;

    /** 【准考证号】 */
    @Column(length = 60)
    public String admissionNo;

    /** 【备注】 */
    @Column(length = 200)
    public String remark;

    /** 【创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}
