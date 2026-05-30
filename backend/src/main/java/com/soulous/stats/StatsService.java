package com.soulous.stats;

import com.soulous.auth.UserAccount;
import com.soulous.focus.FocusSessionRepository;
import com.soulous.focus.FocusStatus;
import com.soulous.pet.ExpLogRepository;
import com.soulous.task.StudyRecordRepository;
import com.soulous.task.SubmissionRepository;
import com.soulous.task.SubmissionStatus;
import com.soulous.task.TaskRepository;
import com.soulous.task.TaskStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【用户学习统计服务层，负责汇总和计算用户的学习数据。
 * 提供个人学习仪表盘所需的各项指标，包括：
 * - 今日任务数、提交数、经验值、学习时长
 * - 任务完成率
 * - 近 7 天学习趋势（含专注时长）
 * - 课程分类统计
 * - 提交通过率
 * - 连续学习天数
 * - 今日专注会话统计】
 */
@Service
public class StatsService {
    private final TaskRepository tasks;
    private final SubmissionRepository submissions;
    private final ExpLogRepository expLogs;
    private final StudyRecordRepository records;
    private final FocusSessionRepository focusSessions;

    StatsService(TaskRepository tasks, SubmissionRepository submissions, ExpLogRepository expLogs,
                 StudyRecordRepository records, FocusSessionRepository focusSessions) {
        this.tasks = tasks;
        this.submissions = submissions;
        this.expLogs = expLogs;
        this.records = records;
        this.focusSessions = focusSessions;
    }

    /**
     * 【生成用户学习统计摘要。
     * 聚合多个数据源，计算并返回以下指标：
     * - todayTasks：今日创建的任务数
     * - todaySubmissions：今日提交的次数
     * - todayExp：今日获得的经验值总量
     * - todayMinutes：今日学习时长（分钟）
     * - completionRate：任务完成率（百分比）
     * - trend：近 7 天学习趋势数据（每天的学习分钟数和专注分钟数）
     * - courses：按课程分类的任务数量统计
     * - approvedCount：审核通过的提交总数
     * - rejectedCount：审核驳回的提交总数
     * - aiApprovalRate：AI 审核通过率（百分比）
     * - consecutiveDays：连续学习天数
     * - todayFocusMinutes：今日专注时长（分钟）
     * - todayFocusSessions：今日专注会话次数】
     *
     * @param user 【当前登录用户】
     * @return 【包含所有统计数据的 Map】
     */
    public Map<String, Object> summary(UserAccount user) {
        var today = LocalDate.now().atStartOfDay();
        var week = LocalDate.now().minusDays(6).atStartOfDay();
        var logs = expLogs.findByUserAndCreatedAtAfter(user, today);
        var weekRecords = records.findByUserAndCreatedAtAfter(user, week);
        var allTasks = tasks.findByUserOrderByCreatedAtDesc(user);
        var completed = allTasks.stream().filter(t -> t.status == TaskStatus.COMPLETED).count();
        var courseMap = new LinkedHashMap<String, Integer>();
        for (var task : allTasks) {
            var course = task.courseName == null || task.courseName.isBlank() ? "未分类" : task.courseName;
            courseMap.put(course, courseMap.getOrDefault(course, 0) + 1);
        }
        var weekFocusSessions = focusSessions.findByUserAndStatusAndCreatedAtAfter(user, FocusStatus.COMPLETED, week);
        var trend = new ArrayList<Map<String, Object>>();
        for (int i = 6; i >= 0; i--) {
            var date = LocalDate.now().minusDays(i);
            var minutes = weekRecords.stream()
                    .filter(r -> date.equals(r.recordDate))
                    .mapToInt(r -> r.studyMinutes == null ? 0 : r.studyMinutes)
                    .sum();
            var focusMinutes = weekFocusSessions.stream()
                    .filter(s -> date.equals(s.createdAt.toLocalDate()))
                    .mapToInt(s -> s.elapsedSeconds == null ? 0 : s.elapsedSeconds / 60)
                    .sum();
            var item = new LinkedHashMap<String, Object>();
            item.put("date", date.toString().substring(5));
            item.put("minutes", minutes);
            item.put("focusMinutes", focusMinutes);
            trend.add(item);
        }

        var approvedStatuses = List.of(SubmissionStatus.AI_APPROVED, SubmissionStatus.MANUAL_APPROVED);
        var rejectedStatuses = List.of(SubmissionStatus.AI_REJECTED, SubmissionStatus.MANUAL_REJECTED);
        long approvedCount = submissions.countByUserAndStatusIn(user, approvedStatuses);
        long rejectedCount = submissions.countByUserAndStatusIn(user, rejectedStatuses);
        long totalSubmissions = submissions.countByUserAndCreatedAtAfter(user, LocalDate.of(2000, 1, 1).atStartOfDay());
        int aiApprovalRate = totalSubmissions == 0 ? 0 : (int) (approvedCount * 100 / totalSubmissions);

        var allRecordDates = records.findByUser(user).stream()
                .map(r -> r.recordDate)
                .filter(d -> d != null)
                .collect(Collectors.toSet());
        int consecutiveDays = 0;
        var checkDate = LocalDate.now();
        while (allRecordDates.contains(checkDate)) {
            consecutiveDays++;
            checkDate = checkDate.minusDays(1);
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("todayTasks", tasks.countByUserAndCreatedAtAfter(user, today));
        result.put("todaySubmissions", submissions.countByUserAndCreatedAtAfter(user, today));
        result.put("todayExp", logs.stream().mapToInt(l -> l.expAmount == null ? 0 : l.expAmount).sum());
        result.put("todayMinutes", weekRecords.stream().filter(r -> r.createdAt.isAfter(today)).mapToInt(r -> r.studyMinutes == null ? 0 : r.studyMinutes).sum());
        result.put("completionRate", allTasks.isEmpty() ? 0 : completed * 100 / allTasks.size());
        result.put("trend", trend);
        result.put("courses", courseMap);
        result.put("approvedCount", approvedCount);
        result.put("rejectedCount", rejectedCount);
        result.put("aiApprovalRate", aiApprovalRate);
        result.put("consecutiveDays", consecutiveDays);
        var todayFocusSessions = focusSessions.findByUserAndStatusAndCreatedAtAfter(user, FocusStatus.COMPLETED, today);
        result.put("todayFocusMinutes", todayFocusSessions.stream().mapToInt(s -> s.elapsedSeconds == null ? 0 : s.elapsedSeconds / 60).sum());
        result.put("todayFocusSessions", todayFocusSessions.size());
        return result;
    }
}
