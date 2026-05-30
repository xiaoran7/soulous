package com.soulous;

import com.soulous.aisession.PlanningSession;
import com.soulous.aisession.PlanningSessionRepository;
import com.soulous.aisession.SessionKind;
import com.soulous.aisession.SessionState;
import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.goal.Goal;
import com.soulous.goal.GoalDtos;
import com.soulous.goal.GoalRepository;
import com.soulous.goal.GoalService;
import com.soulous.goal.GoalStatus;
import com.soulous.task.Difficulty;
import com.soulous.task.StudyTask;
import com.soulous.task.TaskRepository;
import com.soulous.task.TaskStatus;
import com.soulous.task.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【目标服务测试类：验证 GoalService 的核心业务逻辑，包括目标更新（标题/状态/目标日期）、
 * 目标硬删除（级联解绑任务和删除规划会话）、目标详情查询（任务统计）、
 * 以及多用户间的权限隔离（非所有者操作应抛出 ForbiddenException）。
 * 使用 H2 内存数据库进行集成测试，通过辅助方法预置 Goal、Task、PlanningSession 数据。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:goal-service-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class GoalServiceTests {
    @Autowired UserService users;
    @Autowired GoalService service;
    @Autowired GoalRepository goals;
    @Autowired TaskRepository tasks;
    @Autowired PlanningSessionRepository sessions;

    /**
     * 【辅助方法：创建一个具有唯一标识的测试用户，避免测试间数据冲突。】
     */
    private UserAccount newUser(String tag) {
        var unique = tag + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", tag, unique + "@example.com"));
        return users.byToken(auth.token());
    }

    /**
     * 【辅助方法：为指定用户预置一个 Goal 实体。】
     */
    private Goal seedGoal(UserAccount user, String title) {
        var g = new Goal();
        g.user = user;
        g.title = title;
        return goals.save(g);
    }

    /**
     * 【辅助方法：为指定用户和目标预置一个 StudyTask 实体，可指定任务状态。】
     */
    private StudyTask seedTask(UserAccount user, Long goalId, TaskStatus status) {
        var t = new StudyTask();
        t.user = user;
        t.title = "t-" + status;
        t.taskType = TaskType.STUDY;
        t.difficulty = Difficulty.NORMAL;
        t.estimatedMinutes = 30;
        t.baseExp = 20;
        t.status = status;
        t.goalId = goalId;
        return tasks.save(t);
    }

    /**
     * 【辅助方法：为指定用户和目标预置一个 PlanningSession 实体，可指定会话状态。】
     */
    private PlanningSession seedSession(UserAccount user, Goal goal, SessionState state) {
        var s = new PlanningSession();
        s.user = user;
        s.goal = goal;
        s.kind = SessionKind.NEW_GOAL;
        s.state = state;
        return sessions.save(s);
    }

    // ----- 目标更新 --------------------------------------------------------
    // ----- update --------------------------------------------------------

    /**
     * 【测试场景：更新目标的标题、状态和目标日期，验证修改后的字段值正确反映在返回对象中。】
     */
    @Test
    void updateChangesTitleStatusAndTargetDate() {
        var user = newUser("upd");
        var g = seedGoal(user, "原标题");
        var updated = service.update(user, g.id,
                new GoalDtos.UpdateGoalRequest("新标题", LocalDate.of(2026, 12, 31), GoalStatus.PAUSED, null));
        assertThat(updated.title).isEqualTo("新标题");
        assertThat(updated.status).isEqualTo(GoalStatus.PAUSED);
        assertThat(updated.targetDate).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    /**
     * 【测试场景：通过 clearTargetDate=true 参数清除目标的截止日期，
     * 验证更新后 targetDate 为 null。】
     */
    @Test
    void updateClearTargetDateRemovesIt() {
        var user = newUser("clr");
        var g = seedGoal(user, "g");
        g.targetDate = LocalDate.of(2026, 6, 1);
        goals.save(g);

        var updated = service.update(user, g.id,
                new GoalDtos.UpdateGoalRequest(null, null, null, true));
        assertThat(updated.targetDate).isNull();
    }

    /**
     * 【测试场景：更新目标时传入空白标题应抛出 BadRequestException，
     * 确保输入校验正确拦截非法数据。】
     */
    @Test
    void updateRejectsBlankTitle() {
        var user = newUser("blank");
        var g = seedGoal(user, "g");
        assertThatThrownBy(() -> service.update(user, g.id,
                new GoalDtos.UpdateGoalRequest("   ", null, null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * 【测试场景：非目标所有者尝试更新目标时应抛出 ForbiddenException，
     * 确保跨用户权限隔离。】
     */
    @Test
    void updateRejectsForeignUser() {
        var owner = newUser("owner");
        var intruder = newUser("intruder");
        var g = seedGoal(owner, "g");
        assertThatThrownBy(() -> service.update(intruder, g.id,
                new GoalDtos.UpdateGoalRequest("hack", null, null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    // ----- 目标硬删除 ----------------------------------------------------
    // ----- hardDelete ----------------------------------------------------

    /**
     * 【测试场景：硬删除目标时应级联处理关联数据——解绑所有关联任务（goalId 置 null）、
     * 删除所有关联的 PlanningSession，返回值中应包含解绑任务数和关闭会话数。
     * 同时验证删除后通过 ID 查询目标应返回空。】
     */
    @Test
    void hardDeleteRemovesGoalUnbindsTasksAndDeletesAllSessions() {
        var user = newUser("del");
        var g = seedGoal(user, "g");
        var openTask = seedTask(user, g.id, TaskStatus.TODO);
        var doneTask = seedTask(user, g.id, TaskStatus.COMPLETED);
        var draftSession = seedSession(user, g, SessionState.DRAFTING);
        var planSession = seedSession(user, g, SessionState.PLAN_PROPOSED);
        var committedSession = seedSession(user, g, SessionState.COMMITTED);

        var result = service.hardDelete(user, g.id);

        assertThat(result.unboundTasks()).isEqualTo(2);
        assertThat(result.closedSessions()).isEqualTo(3);
        assertThat(goals.findById(g.id)).isEmpty();
        assertThat(tasks.findById(openTask.id).orElseThrow().goalId).isNull();
        assertThat(tasks.findById(doneTask.id).orElseThrow().goalId).isNull();
        assertThat(sessions.findById(draftSession.id)).isEmpty();
        assertThat(sessions.findById(planSession.id)).isEmpty();
        assertThat(sessions.findById(committedSession.id)).isEmpty();
    }

    /**
     * 【测试场景：非所有者尝试硬删除目标时应抛出 ForbiddenException。】
     */
    @Test
    void hardDeleteRejectsForeignUser() {
        var owner = newUser("owner2");
        var intruder = newUser("intruder2");
        var g = seedGoal(owner, "g");
        assertThatThrownBy(() -> service.hardDelete(intruder, g.id))
                .isInstanceOf(ForbiddenException.class);
    }

    // ----- 目标详情 --------------------------------------------------------
    // ----- detail --------------------------------------------------------

    /**
     * 【测试场景：查询目标详情时应返回任务总数（totalTasks）、已完成数（completedTasks）、
     * 未完成数（openTasks）和标题，验证统计计数的准确性。】
     */
    @Test
    void detailReturnsCountsAndTaskList() {
        var user = newUser("det");
        var g = seedGoal(user, "g");
        seedTask(user, g.id, TaskStatus.TODO);
        seedTask(user, g.id, TaskStatus.COMPLETED);

        var body = service.detail(user, g.id);
        assertThat(body.get("totalTasks")).isEqualTo(2);
        assertThat(body.get("completedTasks")).isEqualTo(1);
        assertThat(body.get("openTasks")).isEqualTo(1);
        assertThat(body.get("title")).isEqualTo("g");
    }

    /**
     * 【测试场景：查询已删除目标的详情应抛出 NotFoundException（404）。】
     */
    @Test
    void detailOnDeletedGoalIs404() {
        var user = newUser("det-del");
        var g = seedGoal(user, "g");
        service.hardDelete(user, g.id);
        assertThatThrownBy(() -> service.detail(user, g.id))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * 【测试场景：非所有者查询目标详情时应抛出 ForbiddenException，
     * 确保详情接口同样受权限控制。】
     */
    @Test
    void detailRejectsForeignUser() {
        var owner = newUser("owner3");
        var intruder = newUser("intruder3");
        var g = seedGoal(owner, "g");
        assertThatThrownBy(() -> service.detail(intruder, g.id))
                .isInstanceOf(ForbiddenException.class);
    }
}
