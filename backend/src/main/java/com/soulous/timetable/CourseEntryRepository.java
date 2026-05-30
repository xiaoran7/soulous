package com.soulous.timetable;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 【课表数据仓库：继承 JpaRepository 提供 CourseEntry 的 CRUD，
 *  并定义按用户/学期查询与清空的方法。课表展示按 (dayOfWeek, startSection) 自然排序。】
 */
public interface CourseEntryRepository extends JpaRepository<CourseEntry, Long> {

    /** 【取某用户全部课表，按周几 + 起始节次升序，便于前端直接铺成网格】 */
    List<CourseEntry> findByUserOrderByDayOfWeekAscStartSectionAsc(UserAccount user);

    /** 【取某用户某学期的课表，按周几 + 起始节次升序】 */
    List<CourseEntry> findByUserAndSemesterOrderByDayOfWeekAscStartSectionAsc(UserAccount user, String semester);

    /** 【清空某用户全部课表（导入时 replace=true 且未指定学期的整表覆盖）】 */
    @Transactional
    void deleteByUser(UserAccount user);

    /** 【清空某用户某学期的课表（导入时 replace=true 且指定了学期的按学期覆盖）】 */
    @Transactional
    void deleteByUserAndSemester(UserAccount user, String semester);
}
