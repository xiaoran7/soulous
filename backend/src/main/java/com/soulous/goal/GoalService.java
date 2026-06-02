package com.soulous.goal;

import com.soulous.aisession.PlanningSession;
import com.soulous.aisession.PlanningSessionRepository;
import com.soulous.aisession.SessionTurnRepository;
import com.soulous.auth.UserAccount;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.task.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 【目标业务服务：封装所有学习目标相关的业务逻辑，包括详情查询、更新、硬删除等操作。
 *  硬删除时会级联处理关联的任务解绑和 AI 会话清理，保证数据一致性。
 *  所有操作都进行用户权限校验，确保用户只能操作自己的目标。】
 */
@Service
public class GoalService {
    /** 【目标数据仓库】 */
    private final GoalRepository goals;
    /** 【任务数据仓库，用于查询关联任务和解绑操作】 */
    private final TaskRepository tasks;
    /** 【AI 规划会话数据仓库，用于查询和删除关联的会话】 */
    private final PlanningSessionRepository sessions;
    /** 【会话对话记录数据仓库，用于级联删除会话的对话记录】 */
    private final SessionTurnRepository turns;
    /** 【JSON 解析器，用于校验导入的 distilled memory 是否为合法 JSON】 */
    private final ObjectMapper objectMapper;

    public GoalService(GoalRepository goals, TaskRepository tasks,
                       PlanningSessionRepository sessions, SessionTurnRepository turns,
                       ObjectMapper objectMapper) {
        this.goals = goals;
        this.tasks = tasks;
        this.sessions = sessions;
        this.turns = turns;
        this.objectMapper = objectMapper;
    }

    /**
     * 【获取目标详情：返回目标的完整信息以及关联任务的统计数据。
     *  统计数据包括任务总数、已完成数、进行中数，以及关联任务列表。
     *  使用 LinkedHashMap 保证字段顺序。】
     *
     * @param user 【当前登录用户】
     * @param goalId 【目标 ID】
     * @return 【包含目标详情和任务统计的有序 Map】
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(UserAccount user, Long goalId) {
        var goal = loadOwned(user, goalId);
        var related = tasks.findByUserAndGoalIdOrderByCreatedAtDesc(user, goal.id);
        int total = related.size();
        int completed = (int) related.stream().filter(t -> t.status == com.soulous.task.TaskStatus.COMPLETED).count();
        int open = (int) related.stream().filter(t -> t.status != com.soulous.task.TaskStatus.COMPLETED).count();
        var body = new LinkedHashMap<String, Object>();
        body.put("id", goal.id);
        body.put("title", goal.title);
        body.put("status", goal.status);
        body.put("targetDate", goal.targetDate);
        body.put("distilledMemoryJson", goal.distilledMemoryJson);
        body.put("sessionCount", goal.sessionCount);
        body.put("lastSessionAt", goal.lastSessionAt);
        body.put("createdAt", goal.createdAt);
        body.put("updatedAt", goal.updatedAt);
        body.put("totalTasks", total);
        body.put("completedTasks", completed);
        body.put("openTasks", open);
        body.put("tasks", related);
        return body;
    }

    /**
     * 【更新目标：支持部分更新，只修改请求中明确提供的字段。
     *  标题验证：不能为空，最长200字符。
     *  目标日期：可通过 clearTargetDate=true 清除，或设置新日期。
     *  状态：可直接修改为任何 GoalStatus 枚举值。】
     *
     * @param user 【当前登录用户】
     * @param goalId 【目标 ID】
     * @param req 【更新请求体，为 null 时直接返回原目标】
     * @return 【更新后的目标实体】
     * @throws BadRequestException 【标题为空时抛出】
     */
    @Transactional
    public Goal update(UserAccount user, Long goalId, GoalDtos.UpdateGoalRequest req) {
        var goal = loadOwned(user, goalId);
        if (req == null) return goal;

        if (req.title() != null) {
            var t = req.title().trim();
            if (t.isBlank()) throw new BadRequestException("Title cannot be blank");
            goal.title = t.length() > 200 ? t.substring(0, 200) : t;
        }
        if (Boolean.TRUE.equals(req.clearTargetDate())) {
            goal.targetDate = null;
        } else if (req.targetDate() != null) {
            goal.targetDate = req.targetDate();
        }
        if (req.status() != null) {
            goal.status = req.status();
        }
        goal.updatedAt = LocalDateTime.now();
        return goals.save(goal);
    }

    /**
     * 【导入/覆盖目标记忆：将用户在「设置」中粘贴或上传的 JSON 存入 distilledMemoryJson。
     *  会先校验是否为合法 JSON，非法则抛 BadRequestException。空白内容等同于清空。
     *  返回更新后的目标详情（与 detail 同结构），方便前端直接刷新。】
     *
     * @param user 【当前登录用户】
     * @param goalId 【目标 ID】
     * @param memoryJson 【蒸馏记忆 JSON 文本】
     * @return 【更新后的目标详情 Map】
     * @throws BadRequestException 【JSON 非法时抛出】
     */
    @Transactional
    public Map<String, Object> updateMemory(UserAccount user, Long goalId, String memoryJson) {
        var goal = loadOwned(user, goalId);
        var trimmed = memoryJson == null ? "" : memoryJson.trim();
        if (trimmed.isBlank()) {
            goal.distilledMemoryJson = null;
        } else {
            try {
                objectMapper.readTree(trimmed);
            } catch (Exception e) {
                throw new BadRequestException("目标记忆必须是合法的 JSON");
            }
            goal.distilledMemoryJson = trimmed;
        }
        goal.updatedAt = LocalDateTime.now();
        goals.save(goal);
        return detail(user, goalId);
    }

    /**
     * 【清空目标记忆：将 distilledMemoryJson 置空。返回更新后的目标详情。】
     *
     * @param user 【当前登录用户】
     * @param goalId 【目标 ID】
     * @return 【更新后的目标详情 Map】
     */
    @Transactional
    public Map<String, Object> clearMemory(UserAccount user, Long goalId) {
        var goal = loadOwned(user, goalId);
        goal.distilledMemoryJson = null;
        goal.updatedAt = LocalDateTime.now();
        goals.save(goal);
        return detail(user, goalId);
    }

    /**
     * 【硬删除目标：执行完整的级联删除流程：
     *  1. 解除关联任务与目标的绑定（任务本身不删除）
     *  2. 删除关联的所有 AI 规划会话及其对话记录
     *  3. 删除目标本身
     *  返回删除统计结果。】
     *
     * @param user 【当前登录用户】
     * @param goalId 【目标 ID】
     * @return 【删除结果，包含目标 ID、解除绑定的任务数、关闭的会话数】
     */
    @Transactional
    public GoalDtos.DeleteResult hardDelete(UserAccount user, Long goalId) {
        var goal = loadOwned(user, goalId);

        // 【解除任务与目标的关联，但不删除任务本身】
        int unbound = tasks.unbindFromGoal(user, goal.id);

        // 【级联删除所有关联的 AI 规划会话及其对话记录】
        int closed = 0;
        List<PlanningSession> related = sessions.findByGoalOrderByStartedAtDesc(goal);
        for (PlanningSession s : related) {
            turns.deleteBySession(s);
            sessions.delete(s);
            closed++;
        }

        goals.delete(goal);
        return new GoalDtos.DeleteResult(goal.id, unbound, closed);
    }

    /**
     * 【加载并校验目标所有权：查找目标并验证当前用户是否为所有者。
     *  目标不存在抛 NotFoundException，不属于当前用户抛 ForbiddenException。】
     *
     * @param user 【当前登录用户】
     * @param goalId 【目标 ID】
     * @return 【目标实体】
     * @throws NotFoundException 【目标不存在时抛出】
     * @throws ForbiddenException 【目标不属于当前用户时抛出】
     */
    private Goal loadOwned(UserAccount user, Long goalId) {
        var goal = goals.findById(goalId).orElseThrow(() -> new NotFoundException("Goal not found"));
        if (!Objects.equals(goal.user.id, user.id)) throw new ForbiddenException("Goal belongs to another user");
        return goal;
    }
}
