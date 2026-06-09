package com.soulous.timetable;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 【课程成绩数据仓库：CRUD + 按用户/学期查询与清空。
 *  成绩查询天然跨学期返回全部，同步时整体覆盖（deleteByUser → saveAll）。
 *  列表默认按学期倒序、课程名升序。】
 */
public interface GradeEntryRepository extends JpaRepository<GradeEntry, Long> {

    /** 【取某用户全部成绩，学期倒序 + 课程名升序】 */
    List<GradeEntry> findByUserOrderBySemesterDescCourseNameAsc(UserAccount user);

    /** 【取某用户某学期的成绩，按课程名升序】 */
    List<GradeEntry> findByUserAndSemesterOrderByCourseNameAsc(UserAccount user, String semester);

    /** 【清空某用户全部成绩（同步时整体覆盖）】 */
    @Transactional
    void deleteByUser(UserAccount user);
}
