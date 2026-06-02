package com.soulous;

import com.soulous.admin.AdminAuditAction;
import com.soulous.admin.AdminReviewRequest;
import com.soulous.admin.AdminService;
import com.soulous.admin.AuditTargetType;
import com.soulous.ai.AiReview;
import com.soulous.auth.ProfileRequest;
import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserRole;
import com.soulous.auth.UserService;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.pet.PetService;
import com.soulous.pet.PetStatus;
import com.soulous.task.Difficulty;
import com.soulous.task.SubmissionStatus;
import com.soulous.task.SubmitTaskRequest;
import com.soulous.task.TaskRequest;
import com.soulous.task.TaskService;
import com.soulous.task.TaskSubmission;
import com.soulous.task.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:soulous-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
/**
 * 【Soulous 应用集成测试类：覆盖用户资料更新、任务 CRUD、任务提交与宠物经验联动、
 * 被拒绝提交对宠物状态的影响、管理员人工审批自定义经验值、提交详情含 AI 审核及权限隔离、
 * 已提交任务的硬删除、任务生命周期触发宠物事件等核心业务流程。
 * 使用 H2 内存数据库和 @SpringBootTest 进行全栈集成验证。】
 */
class SoulousApplicationTests {
    @Autowired UserService users;
    @Autowired TaskService tasks;
    @Autowired PetService pets;
    @Autowired AdminService admins;

    /**
     * 【测试场景：用户注册后可以通过 updateProfile 修改昵称和邮箱，
     * 修改后通过 view 方法返回的视图也应反映最新数据。】
     */
    @Test
    void userProfileCanBeUpdated() {
        var unique = "profile" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!","初始昵称", unique + "@example.com"));
        var user = users.byToken(auth.token());

        var updated = users.updateProfile(user, new ProfileRequest("新的昵称", "new-" + unique + "@example.com", null));
        var view = users.view(updated);

        assertThat(updated.nickname).isEqualTo("新的昵称");
        assertThat(updated.email).startsWith("new-");
        assertThat(view).containsEntry("nickname", "新的昵称");
    }

    /**
     * 【测试场景：任务的完整 CRUD 流程——创建任务、更新任务标题/基础经验等字段、
     * 验证列表中存在该任务、删除后查询应抛出 NotFoundException。】
     */
    @Test
    void taskCrudCanUpdateAndDelete() {
        var unique = "crud" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!","任务用户", unique + "@example.com"));
        var user = users.byToken(auth.token());
        var task = tasks.create(user, new TaskRequest("整理错题", "复盘一组练习题", TaskType.REVIEW, Difficulty.EASY, "算法", null, 20, 12, null, null));

        var updated = tasks.update(user, task.id, new TaskRequest("整理动态规划错题", "补齐状态转移和边界条件", TaskType.REVIEW, Difficulty.NORMAL, "算法", null, 35, 24, null, null));

        assertThat(updated.title).isEqualTo("整理动态规划错题");
        assertThat(updated.baseExp).isEqualTo(24);
        assertThat(tasks.list(user)).extracting(t -> t.id).contains(task.id);

        tasks.delete(user, task.id);

        assertThatThrownBy(() -> tasks.one(user, task.id)).isInstanceOf(NotFoundException.class);
    }

    /**
     * 【测试场景：提交任务凭证后，宠物应获得经验值（currentExp > 0），
     * 同时返回结果中应包含 submission、review、task 三个键。】
     */
    @Test
    void taskSubmissionCanAwardPetExp() {
        var unique = "tester" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!","测试用户", unique + "@example.com"));
        var user = users.byToken(auth.token());
        var task = tasks.create(user, new TaskRequest("复习数据结构栈和队列", "总结栈和队列的区别", TaskType.STUDY, Difficulty.NORMAL, "数据结构", null, 30, 30, null, null));
        tasks.start(user, task.id);
        var result = tasks.submit(user, task.id, new SubmitTaskRequest("我复习了栈的后进先出、队列的先进先出，并整理了循环队列判满条件。", "", "", 35, "", null));
        var pet = pets.get(user);
        assertThat(result).containsKeys("submission", "review", "task");
        assertThat(pet.currentExp).isGreaterThan(0);
    }

    /**
     * 【测试场景：提交内容过短导致被 AI 审核拒绝后，宠物状态应变为 SAD，
     * 心情值下降，经验日志中应记录"凭证未通过"且经验值为 0。】
     */
    @Test
    void rejectedSubmissionUpdatesPetFeedbackState() {
        var unique = "rejecter" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!","反馈用户", unique + "@example.com"));
        var user = users.byToken(auth.token());
        var task = tasks.create(user, new TaskRequest("学习二叉树遍历", "提交学习总结", TaskType.STUDY, Difficulty.NORMAL, "数据结构", null, 30, 30, null, null));
        tasks.start(user, task.id);

        tasks.submit(user, task.id, new SubmitTaskRequest("短", "", "", 5, "", null));

        var pet = pets.get(user);
        assertThat(pet.status).isEqualTo(PetStatus.SAD);
        assertThat(pet.mood).isLessThan(80);
        assertThat(pets.logs(user)).anySatisfy(log -> {
            assertThat(log.expAmount).isZero();
            assertThat(log.reason).contains("凭证未通过");
        });
    }

    /**
     * 【测试场景：管理员人工审批通过提交时，可指定自定义经验值（如 17），
     * 宠物经验应精确等于该值，状态变为 MANUAL_APPROVED，
     * 且审核日志（AdminAudit）中应记录操作人、审批动作、经验值和评语。】
     */
    @Test
    void adminApprovalUsesCustomExpAmount() {
        var unique = "manual" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!","人工复核用户", unique + "@example.com"));
        var user = users.byToken(auth.token());
        var task = tasks.create(user, new TaskRequest("补交学习凭证", "等待管理员确认", TaskType.STUDY, Difficulty.NORMAL, "数学", null, 20, 30, null, null));
        tasks.start(user, task.id);
        var result = tasks.submit(user, task.id, new SubmitTaskRequest("短", "", "", 5, "", null));
        var submission = (TaskSubmission) result.get("submission");

        var adminAccount = users.ensureUser("admin", "admin123", "审核老师", UserRole.ADMIN);
        var approved = admins.approve(adminAccount, submission.id, new AdminReviewRequest(17, "人工确认补充材料有效"));
        var pet = pets.get(user);

        assertThat(((TaskSubmission) approved.get("submission")).status).isEqualTo(SubmissionStatus.MANUAL_APPROVED);
        assertThat(pet.currentExp).isEqualTo(17);
        assertThat(pets.logs(user)).anySatisfy(log -> {
            assertThat(log.expAmount).isEqualTo(17);
            assertThat(log.reason).contains("人工复核通过");
        });

        var auditEntries = admins.auditForSubmission(submission.id);
        assertThat(auditEntries).isNotEmpty();
        var latest = auditEntries.get(0);
        assertThat(latest.action).isEqualTo(AdminAuditAction.APPROVE);
        assertThat(latest.targetType).isEqualTo(AuditTargetType.SUBMISSION);
        assertThat(latest.targetId).isEqualTo(submission.id);
        assertThat(latest.expAmount).isEqualTo(17);
        assertThat(latest.adminUsername).isEqualTo("admin");
        assertThat(latest.resultStatus).isEqualTo(SubmissionStatus.MANUAL_APPROVED.name());
        assertThat(latest.comment).contains("人工确认");
    }

    /**
     * 【测试场景：提交详情接口应返回 submission、task、review 三个对象，
     * 其中 AI 审核建议（suggestion）不为空；同时验证非提交者访问时应抛出 ForbiddenException，
     * 确保数据所有权隔离。】
     */
    @Test
    void submissionDetailIncludesAiReviewAndProtectsOwnership() {
        var unique = "detail" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!","反馈详情用户", unique + "@example.com"));
        var otherAuth = users.register(new RegisterRequest(unique + "other", "Passw0rd!","其他用户", unique + "other@example.com"));
        var user = users.byToken(auth.token());
        var other = users.byToken(otherAuth.token());
        var task = tasks.create(user, new TaskRequest("查看审核反馈", "提交后查看 AI 说明", TaskType.STUDY, Difficulty.NORMAL, "英语", null, 20, 25, null, null));
        tasks.start(user, task.id);
        var result = tasks.submit(user, task.id, new SubmitTaskRequest("短", "", "", 5, "", null));
        var submission = (TaskSubmission) result.get("submission");

        var detail = tasks.submissionDetail(user, submission.id);

        assertThat(detail).containsKeys("submission", "task", "review");
        assertThat(((AiReview) detail.get("review")).suggestion).isNotBlank();
        assertThatThrownBy(() -> tasks.submissionDetail(other, submission.id)).isInstanceOf(ForbiddenException.class);
    }

    /**
     * 【测试场景：已有提交记录的任务仍可被硬删除，删除后任务列表中不应再包含该任务 ID。】
     */
    @Test
    void submittedTaskCanBeHardDeleted() {
        var unique = "hardelete" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!","硬删除用户", unique + "@example.com"));
        var user = users.byToken(auth.token());
        var task = tasks.create(user, new TaskRequest("可删除任务", "已有提交记录", TaskType.STUDY, Difficulty.NORMAL, "英语", null, 20, 25, null, null));
        tasks.start(user, task.id);
        tasks.submit(user, task.id, new SubmitTaskRequest("短", "", "", 5, "", null));

        tasks.delete(user, task.id);
        assertThat(tasks.list(user)).extracting(t -> t.id).doesNotContain(task.id);
    }

    /**
     * 【测试场景：任务生命周期（开始→提交）应触发对应的宠物事件日志，
     * 包括 TASK_STARTED（开始任务）和 SUBMITTED_FOR_REVIEW（提交复核），
     * 同时宠物状态在开始时应变为 WORKING。】
     */
    @Test
    void taskLifecycleCreatesPetEvents() {
        var unique = "worker" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!","工作用户", unique + "@example.com"));
        var user = users.byToken(auth.token());
        var task = tasks.create(user, new TaskRequest("完成算法练习", "写一段练习总结", TaskType.CODING, Difficulty.NORMAL, "算法", null, 30, 30, null, null));

        tasks.start(user, task.id);
        assertThat(pets.get(user).status).isEqualTo(PetStatus.WORKING);

        tasks.submit(user, task.id, new SubmitTaskRequest("我完成了算法练习，整理了递归边界、复杂度分析和测试结果。", "", "", 25, "", null));

        assertThat(pets.logs(user)).anySatisfy(log -> {
            assertThat(log.eventType).isEqualTo("TASK_STARTED");
            assertThat(log.reason).contains("开始任务");
        }).anySatisfy(log -> {
            assertThat(log.eventType).isEqualTo("SUBMITTED_FOR_REVIEW");
            assertThat(log.reason).contains("提交复核");
        });
    }
}
