package com.soulous.task;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 【学习记录仓库接口——负责对 study_record 表进行持久化操作。
 *  继承 JpaRepository 获得基础 CRUD 能力，并自定义若干按用户、时间、任务查询的方法。
 *  通常由 TaskService 在记录学习时长 / 统计学习数据时调用。】
 *
 * <p>English: Spring Data JPA repository for {@link StudyRecord} entities.</p>
 */
public interface StudyRecordRepository extends JpaRepository<StudyRecord, Long> {

    /**
     * 【查询指定用户在给定时间点之后的所有学习记录，用于统计"最近 N 天"的学习情况。】
     *
     * @param user  【关联的用户账号】
     * @param since 【起始时间点，只返回 createdAt > since 的记录】
     * @return 【满足条件的学习记录列表】
     */
    List<StudyRecord> findByUserAndCreatedAtAfter(UserAccount user, LocalDateTime since);

    /**
     * 【查询指定用户的全部学习记录，不分时间范围。】
     *
     * @param user 【关联的用户账号】
     * @return 【该用户所有学习记录列表】
     */
    List<StudyRecord> findByUser(UserAccount user);

    /**
     * 【判断是否存在与指定任务关联的学习记录，用于在删除任务前检查是否有引用。】
     *
     * @param task 【要检查的學習任务】
     * @return 【存在关联记录返回 true，否则返回 false】
     */
    boolean existsByTask(StudyTask task);

    /**
     * 【根据任务删除所有关联的学习记录。
     *  标注 @Modifying + @Transactional 使其成为可写事务操作。
     *  通常在删除任务前由 TaskService 调用，以清理关联数据。】
     *
     * @param task 【要删除其学习记录的任務】
     */
    @Modifying
    @Transactional
    void deleteByTask(StudyTask task);
}
