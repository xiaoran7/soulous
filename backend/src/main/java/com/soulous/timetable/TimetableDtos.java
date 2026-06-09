package com.soulous.timetable;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 【课表模块的数据传输对象（DTO）集合。
 *  集中定义导入 / 查询 / AI 排课接口的请求与响应 record，
 *  避免直接对外暴露 JPA 实体（防止 user 关联图被序列化）。】
 */
public final class TimetableDtos {
    private TimetableDtos() {}

    /**
     * 【课表导入请求。
     *  html：用户从教务系统课表页复制的 HTML 源码（或纯表格片段）；
     *  semester：可选学期标识，如 "2025-2026-2"；
     *  replace：true 时先清空旧课表（指定 semester 则只清该学期）再写入。】
     */
    /**
     * 【课表同步请求。
     *  username：学号/账号；
     *  password：密码。】
     */
    public record SyncRequest(
            @NotBlank String username,
            @NotBlank String password
     ) {}

    /**
     * 【手动新增一节课的请求。
     *  用户在课表网格上手工补一节课（如临时调课、活动占用时段）。
     *  courseName与dayOfWeek必填，其余可空——字段对齐 {@link CourseView}。】
     */
    public record CourseCreateRequest(
            /** 【课程名称，必填】 */
            @NotBlank String courseName,
            /** 【星期几 1-7，周一=1，必填】 */
            int dayOfWeek,
            String teacher,
            String location,
            Integer startSection,
            Integer endSection,
            String startTime,
            String endTime,
            String weeks,
            String weekParity,
            String semester
    ) {}

    /**
     * 【课表条目的对外视图（不含 user 关联），用于列表与导入结果返回。】
     */
    public record CourseView(
            Long id,
            String courseName,
            String teacher,
            String location,
            int dayOfWeek,
            Integer startSection,
            Integer endSection,
            String startTime,
            String endTime,
            String weeks,
            String weekParity,
            String semester
    ) {
        /** 【从实体投影成视图】 */
        public static CourseView of(CourseEntry c) {
            return new CourseView(
                    c.id, c.courseName, c.teacher, c.location, c.dayOfWeek,
                    c.startSection, c.endSection, c.startTime, c.endTime,
                    c.weeks, c.weekParity == null ? null : c.weekParity.name(), c.semester);
        }
    }

    /**
     * 【同步结果：写入条数 + 学期 + 开学日期 + 解析出的课表，以及一并抓取的考试/成绩条数。
     *  examCount/gradeCount 让前端在同步后给出"顺带拉到 N 场考试、M 门成绩"的提示。】
     */
    public record SyncResult(int count, String semester, String weekStart, List<CourseView> courses,
                             int examCount, int gradeCount) {}

    /**
     * 【考试安排的对外视图（不含 user 关联）。】
     */
    public record ExamView(
            Long id,
            String semester,
            String courseName,
            String courseCode,
            String teacher,
            String examTime,
            String room,
            String campus,
            String seatNo,
            String session,
            String admissionNo,
            String remark
    ) {
        /** 【从实体投影成视图】 */
        public static ExamView of(ExamEntry e) {
            return new ExamView(
                    e.id, e.semester, e.courseName, e.courseCode, e.teacher,
                    e.examTime, e.room, e.campus, e.seatNo, e.session, e.admissionNo, e.remark);
        }
    }

    /**
     * 【课程成绩的对外视图（不含 user 关联）。学分/绩点等保留原始字符串，由前端按需解析。】
     */
    public record GradeView(
            Long id,
            String semester,
            String courseCode,
            String courseName,
            String department,
            String score,
            String scoreFlag,
            String credit,
            String gpa,
            String totalHours,
            String assessMethod,
            String examNature,
            String courseAttr,
            String courseNature
    ) {
        /** 【从实体投影成视图】 */
        public static GradeView of(GradeEntry g) {
            return new GradeView(
                    g.id, g.semester, g.courseCode, g.courseName, g.department,
                    g.score, g.scoreFlag, g.credit, g.gpa, g.totalHours,
                    g.assessMethod, g.examNature, g.courseAttr, g.courseNature);
        }
    }
}
