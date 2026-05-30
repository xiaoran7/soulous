package com.soulous.appeal;

import com.soulous.auth.UserAccount;
import com.soulous.task.TaskSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.List;

/**
 * 【申诉记录的数据访问层接口，继承 JpaRepository 提供基本 CRUD 操作。
 * 同时定义了按用户查询、按创建时间倒序查询、以及批量删除等自定义查询方法。】
 */
public interface AppealRepository extends JpaRepository<Appeal, Long> {

    /**
     * 【按用户查询所有申诉记录，按创建时间倒序排列。】
     *
     * @param user 【要查询的用户】
     * @return 【该用户的申诉记录列表】
     */
    List<Appeal> findByUserOrderByCreatedAtDesc(UserAccount user);

    /**
     * 【查询所有申诉记录，按创建时间倒序排列，用于管理员后台列表。】
     *
     * @return 【全部申诉记录列表】
     */
    List<Appeal> findAllByOrderByCreatedAtDesc();

    /**
     * 【根据关联的任务提交记录集合批量删除申诉记录。
     * 用于级联删除场景，如删除提交记录时同步清理相关申诉。
     * 使用 @Modifying + @Transactional 注解执行批量删除操作。】
     *
     * @param submissions 【要删除申诉的任务提交记录集合】
     */
    @Modifying
    @Transactional
    void deleteBySubmissionIn(Collection<TaskSubmission> submissions);
}
