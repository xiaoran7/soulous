package com.soulous.ai;

import com.soulous.task.TaskSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.Optional;

/**
 * 【AI 审核结果的数据访问层接口。
 *
 * <p>继承 {@link JpaRepository}，提供对 {@link AiReview} 实体的标准 CRUD 操作，
 * 同时定义了按提交记录查询和批量删除的自定义方法。</p>
 *
 * <p>与 {@link com.soulous.task.TaskSubmission} 存在一对一关联关系——
 * 每个任务提交最多对应一条 AI 审核记录。</p>
 */
public interface AiReviewRepository extends JpaRepository<AiReview, Long> {

    /**
     * 【根据任务提交记录查找对应的 AI 审核结果。
     *
     * @param submission 【任务提交实体，不能为空】
     * @return 【包含 AiReview 的 Optional；若该提交无审核记录则返回 Optional.empty()】
     */
    Optional<AiReview> findBySubmission(TaskSubmission submission);

    /**
     * 【批量删除指定提交记录集合关联的所有 AI 审核结果。
     *
     * <p>使用 {@code @Modifying} 标记为修改操作，配合 {@code @Transactional} 确保事务一致性。
     * 通常在批量清理任务提交时级联调用。</p>
     *
     * @param submissions 【待删除审核记录关联的任务提交集合】
     */
    @Modifying
    @Transactional
    void deleteBySubmissionIn(Collection<TaskSubmission> submissions);
}
