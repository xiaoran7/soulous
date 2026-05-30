package com.soulous.admin;

import com.soulous.appeal.AppealStatus;
import com.soulous.audit.AuditAction;
import com.soulous.audit.AuditService;
import com.soulous.auth.AdminCreateUserRequest;
import com.soulous.auth.UserRole;
import com.soulous.auth.UserService;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.health.MetricsSnapshotService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 【管理员后台控制器，处理所有管理员专属的 HTTP 请求。
 * 包括用户管理（创建用户、修改角色）、提交审核（批准/驳回/需补充）、
 * 申诉审核、审计日志查询、系统指标监控等功能。
 * 所有接口均需管理员权限，通过 admin(request) 方法进行权限校验。】
 */
@RestController
@RequestMapping("/api/admin")
class AdminController extends BaseController {
    private final AdminService adminService;
    private final AuditService audit;
    private final MetricsSnapshotService metricsSnapshot;

    AdminController(UserService users, AdminService adminService, AuditService audit,
                    MetricsSnapshotService metricsSnapshot) {
        super(users);
        this.adminService = adminService;
        this.audit = audit;
        this.metricsSnapshot = metricsSnapshot;
    }

    /**
     * 【管理员创建用户接口。
     * 解析请求中的角色参数，校验其有效性后调用 UserService 创建用户，
     * 并记录 ADMIN_CREATE_USER 审计日志。】
     *
     * @param request 【HTTP 请求对象，用于管理员权限校验】
     * @param body    【用户创建请求体，包含用户名、密码、昵称、角色】
     * @return 【创建成功的用户视图对象】
     */
    @PostMapping("/users")
    Object createUser(HttpServletRequest request, @Valid @RequestBody AdminCreateUserRequest body) {
        var actor = admin(request);
        UserRole role;
        try {
            role = body.role() == null || body.role().isBlank() ? UserRole.USER : UserRole.valueOf(body.role().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("无效的角色：" + body.role());
        }
        var created = users.createByAdmin(body.username(), body.password(), body.nickname(), role);
        audit.record(AuditAction.ADMIN_CREATE_USER, actor, "USER", created.id, request, true,
                "created username=" + created.username + " role=" + created.role);
        return users.view(created);
    }

    /**
     * 【管理员修改用户角色接口。
     * 校验角色参数有效性后调用 UserService 更新用户角色，
     * 并记录 ADMIN_UPDATE_USER_ROLE 审计日志。】
     *
     * @param request 【HTTP 请求对象，用于管理员权限校验】
     * @param id      【要修改角色的用户ID】
     * @param body    【请求体，包含新的 role 字段】
     * @return 【更新后的用户视图对象】
     */
    @PatchMapping("/users/{id}/role")
    Object updateUserRole(HttpServletRequest request, @PathVariable Long id,
                          @RequestBody Map<String, Object> body) {
        var actor = admin(request);
        var roleRaw = body.get("role");
        if (roleRaw == null || roleRaw.toString().isBlank()) {
            throw new BadRequestException("role 不能为空");
        }
        UserRole role;
        try {
            role = UserRole.valueOf(roleRaw.toString().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("无效的角色：" + roleRaw);
        }
        var updated = users.updateRoleByAdmin(id, role);
        audit.record(AuditAction.ADMIN_UPDATE_USER_ROLE, actor, "USER", updated.id, request, true,
                "set role=" + updated.role + " for username=" + updated.username);
        return users.view(updated);
    }

    /**
     * 【查询待审核提交列表接口。
     * scope 参数控制筛选范围：默认 "todo" 只返回待审核和AI驳回的提交，"all" 返回全部。】
     *
     * @param request 【HTTP 请求对象，用于管理员权限校验】
     * @param scope   【筛选范围，"todo" 或 "all"】
     * @return 【提交列表，包含宠物等级信息】
     */
    @GetMapping("/submissions")
    Object submissions(HttpServletRequest request, @RequestParam(defaultValue = "todo") String scope) {
        admin(request);
        return adminService.submissions(scope);
    }

    /**
     * 【查询单个提交详情接口。
     * 返回提交记录、关联任务、用户信息、AI审核结果和宠物等级。】
     *
     * @param request 【HTTP 请求对象，用于管理员权限校验】
     * @param id      【提交记录ID】
     * @return 【包含 submission、task、user、review、petLevel 的详情 Map】
     */
    @GetMapping("/submissions/{id}")
    Object submission(HttpServletRequest request, @PathVariable Long id) {
        admin(request);
        return adminService.submission(id);
    }

    /**
     * 【批准提交接口。
     * 管理员审核通过，为用户发放经验值，完成任务，记录审核日志。】
     *
     * @param request 【HTTP 请求对象】
     * @param id      【提交记录ID】
     * @param body    【审核请求体，包含可选的 expAmount 和 comment】
     * @return 【更新后的提交和任务信息】
     */
    @PostMapping("/submissions/{id}/approve")
    Object approve(HttpServletRequest request, @PathVariable Long id, @RequestBody AdminReviewRequest body) {
        var admin = admin(request);
        return adminService.approve(admin, id, body);
    }

    /**
     * 【驳回提交接口。
     * 管理员审核不通过，标记提交和任务为手动驳回。】
     *
     * @param request 【HTTP 请求对象】
     * @param id      【提交记录ID】
     * @param body    【审核请求体】
     * @return 【更新后的提交信息】
     */
    @PostMapping("/submissions/{id}/reject")
    Object reject(HttpServletRequest request, @PathVariable Long id, @RequestBody AdminReviewRequest body) {
        var admin = admin(request);
        return adminService.reject(admin, id, body);
    }

    /**
     * 【要求补充材料接口。
     * 管理员要求用户提供更多证据或信息。】
     *
     * @param request 【HTTP 请求对象】
     * @param id      【提交记录ID】
     * @param body    【审核请求体】
     * @return 【更新后的提交信息】
     */
    @PostMapping("/submissions/{id}/need-more")
    Object needMore(HttpServletRequest request, @PathVariable Long id, @RequestBody AdminReviewRequest body) {
        var admin = admin(request);
        return adminService.needMore(admin, id, body);
    }

    /**
     * 【查询最近的管理员审核日志接口。
     * 返回最近 100 条审核操作记录。】
     *
     * @param request 【HTTP 请求对象，用于管理员权限校验】
     * @return 【审核日志列表】
     */
    @GetMapping("/audit")
    Object audit(HttpServletRequest request) {
        admin(request);
        return adminService.recentAudit();
    }

    /**
     * 【查询指定提交的审核历史接口。
     * 返回该提交相关的所有审核操作记录。】
     *
     * @param request 【HTTP 请求对象，用于管理员权限校验】
     * @param id      【提交记录ID】
     * @return 【该提交的审核日志列表】
     */
    @GetMapping("/submissions/{id}/audit")
    Object submissionAudit(HttpServletRequest request, @PathVariable Long id) {
        admin(request);
        return adminService.auditForSubmission(id);
    }

    /**
     * 【查询申诉列表接口。
     * scope 参数控制筛选范围：默认 "todo" 只返回待审核申诉，"all" 返回全部。】
     *
     * @param request 【HTTP 请求对象，用于管理员权限校验】
     * @param scope   【筛选范围，"todo" 或 "all"】
     * @return 【申诉列表，包含关联的提交、任务、用户、宠物等级和AI审核信息】
     */
    @GetMapping("/appeals")
    Object appeals(HttpServletRequest request, @RequestParam(defaultValue = "todo") String scope) {
        admin(request);
        return adminService.appeals(scope);
    }

    /**
     * 【查询系统运行指标接口。
     * 返回系统健康状态快照，包括内存、线程、数据库连接池等指标。】
     *
     * @param request 【HTTP 请求对象，用于管理员权限校验】
     * @return 【系统指标快照】
     */
    @GetMapping("/metrics")
    Object metrics(HttpServletRequest request) {
        admin(request);
        return metricsSnapshot.snapshot();
    }

    /**
     * 【审核申诉接口。
     * 根据审核状态（通过/驳回/需补充）执行不同的业务逻辑，
     * 包括更新申诉状态、处理关联的提交和任务、发放经验值、发送通知等。
     * 支持可选的经验值参数（expAmount）。】
     *
     * @param request 【HTTP 请求对象】
     * @param id      【申诉记录ID】
     * @param body    【请求体，包含 status（审核状态）、comment（审核意见）、expAmount（可选经验值）】
     * @return 【更新后的申诉记录】
     */
    @PostMapping("/appeals/{id}/review")
    Object reviewAppeal(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        var admin = admin(request);
        var statusValue = body.get("status");
        var status = AppealStatus.valueOf(statusValue == null ? "NEED_MORE" : statusValue.toString());
        var comment = body.get("comment") == null ? "" : body.get("comment").toString();
        Integer expAmount = null;
        var expRaw = body.get("expAmount");
        if (expRaw instanceof Number n) expAmount = n.intValue();
        else if (expRaw != null) {
            try { expAmount = Integer.parseInt(expRaw.toString()); } catch (NumberFormatException ignored) {}
        }
        return adminService.reviewAppeal(admin, id, status, expAmount, comment);
    }
}
