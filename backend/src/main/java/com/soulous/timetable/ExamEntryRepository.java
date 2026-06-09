package com.soulous.timetable;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 【考试安排数据仓库：CRUD + 按用户/学期查询与清空。
 *  列表默认按学期倒序、考试时间升序，便于前端铺成「按学期分组、时间从早到晚」的列表。】
 */
public interface ExamEntryRepository extends JpaRepository<ExamEntry, Long> {

    /** 【取某用户全部考试安排，学期倒序 + 考试时间升序】 */
    List<ExamEntry> findByUserOrderBySemesterDescExamTimeAsc(UserAccount user);

    /** 【取某用户某学期的考试安排，按考试时间升序】 */
    List<ExamEntry> findByUserAndSemesterOrderByExamTimeAsc(UserAccount user, String semester);

    /** 【清空某用户某学期的考试安排（同步时按学期覆盖）】 */
    @Transactional
    void deleteByUserAndSemester(UserAccount user, String semester);
}
