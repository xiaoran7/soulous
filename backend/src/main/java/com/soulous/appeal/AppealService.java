package com.soulous.appeal;

import com.soulous.auth.UserAccount;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.task.SubmissionRepository;
import com.soulous.task.TaskRepository;
import com.soulous.task.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 【申诉业务服务层，负责处理用户对任务提交结果的申诉流程。
 * 包括创建申诉、查询个人申诉列表等核心业务逻辑。
 * 创建申诉时会校验提交记录归属、处理截图 URL 列表的拼接，
 * 并将对应任务状态置为 APPEALING（申诉中）。】
 */
@Service
public class AppealService {
    private final AppealRepository appeals;
    private final SubmissionRepository submissions;
    private final TaskRepository tasks;

    AppealService(AppealRepository appeals, SubmissionRepository submissions, TaskRepository tasks) {
        this.appeals = appeals;
        this.submissions = submissions;
        this.tasks = tasks;
    }

    /**
     * 【创建申诉记录。
     * 1. 根据 submissionId 查找对应的提交记录，若不存在则抛出 NotFoundException；
     * 2. 校验提交记录是否属于当前用户，若不匹配则抛出 ForbiddenException；
     * 3. 构建 Appeal 实体，设置申诉原因，并将截图 URL 列表拼接为逗号分隔的字符串存储；
     * 4. 将关联任务状态修改为 APPEALING，保存任务和申诉记录。
     * 整个过程在同一事务中执行，保证数据一致性。】
     *
     * @param user    【当前登录用户，用于校验提交记录归属】
     * @param request 【申诉请求体，包含提交ID、申诉原因、截图URL列表】
     * @return 【保存后的 Appeal 实体】
     */
    @Transactional
    public Appeal create(UserAccount user, AppealRequest request) {
        var submission = submissions.findById(request.submissionId())
                .orElseThrow(() -> new NotFoundException("Submission not found"));
        if (!Objects.equals(submission.user.id, user.id)) throw new ForbiddenException("Submission belongs to another user");
        var appeal = new Appeal();
        appeal.user = user;
        appeal.submission = submission;
        appeal.appealReason = request.appealReason();
        if (request.screenshotUrls() != null && !request.screenshotUrls().isEmpty()) {
            var sb = new StringBuilder();
            for (var u : request.screenshotUrls()) {
                if (u == null || u.isBlank()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(u.trim());
            }
            appeal.screenshotUrls = sb.length() == 0 ? null : sb.toString();
        }
        submission.task.status = TaskStatus.APPEALING;
        tasks.save(submission.task);
        return appeals.save(appeal);
    }

    /**
     * 【查询当前用户的所有申诉记录，按创建时间倒序排列。】
     *
     * @param user 【当前登录用户】
     * @return 【该用户的申诉记录列表，最新的排在前面】
     */
    public List<Appeal> mine(UserAccount user) {
        return appeals.findByUserOrderByCreatedAtDesc(user);
    }
}
