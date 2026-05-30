package com.soulous.task;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 【学习任务 REST 控制器：提供任务的完整 CRUD 操作和生命周期管理接口】
 *
 * 【设计思路：继承 {@link BaseController} 获取用户认证能力（current() 方法），
 * 所有端点都需要用户登录。路由前缀为 /api/tasks，
 * 操作委托给 {@link TaskService} 处理业务逻辑。】
 */
@RestController
@RequestMapping("/api/tasks")
class TaskController extends BaseController {
    /** 【任务服务，处理所有业务逻辑】 */
    private final TaskService tasks;

    /**
     * 【构造注入用户服务和任务服务】
     *
     * @param users 【用户服务，用于 BaseController 的认证功能】
     * @param tasks 【任务服务】
     */
    TaskController(UserService users, TaskService tasks) {
        super(users);
        this.tasks = tasks;
    }

    /**
     * 【获取当前用户的所有任务列表，按创建时间倒序排列】
     *
     * @param request 【HTTP 请求，用于提取当前登录用户】
     * @return 【任务列表】
     */
    @GetMapping
    Object list(HttpServletRequest request) {
        return tasks.list(current(request));
    }

    /**
     * 【创建新学习任务】
     *
     * @param request 【HTTP 请求，用于提取当前登录用户】
     * @param body    【任务请求体，包含标题、描述等字段，使用 @Valid 校验】
     * @return 【创建成功的任务实体】
     */
    @PostMapping
    Object create(HttpServletRequest request, @Valid @RequestBody TaskRequest body) {
        return tasks.create(current(request), body);
    }

    /**
     * 【获取单个任务详情】
     *
     * @param request 【HTTP 请求】
     * @param id      【任务 ID 路径变量】
     * @return 【任务详情】
     */
    @GetMapping("/{id}")
    Object one(HttpServletRequest request, @PathVariable Long id) {
        return tasks.one(current(request), id);
    }

    /**
     * 【更新已有任务，仅允许编辑 TODO/NEED_MORE/AI_REJECTED 状态的任务】
     *
     * @param request 【HTTP 请求】
     * @param id      【任务 ID】
     * @param body    【更新内容】
     * @return 【更新后的任务实体】
     */
    @PutMapping("/{id}")
    Object update(HttpServletRequest request, @PathVariable Long id, @Valid @RequestBody TaskRequest body) {
        return tasks.update(current(request), id, body);
    }

    /**
     * 【删除任务，级联删除关联的提交记录、审核记录、申诉记录、经验值日志和学习记录】
     *
     * @param request 【HTTP 请求】
     * @param id      【任务 ID】
     * @return 【删除确认信息 {"deleted": true}】
     */
    @DeleteMapping("/{id}")
    Object delete(HttpServletRequest request, @PathVariable Long id) {
        tasks.delete(current(request), id);
        return Map.of("deleted", true);
    }

    /**
     * 【开始任务，将状态从 TODO 变为 DOING，记录开始时间，通知宠物系统】
     *
     * @param request 【HTTP 请求】
     * @param id      【任务 ID】
     * @return 【更新后的任务实体】
     */
    @PostMapping("/{id}/start")
    Object start(HttpServletRequest request, @PathVariable Long id) {
        return tasks.start(current(request), id);
    }

    /**
     * 【暂停任务，仅 DOING 状态的任务可暂停】
     *
     * @param request 【HTTP 请求】
     * @param id      【任务 ID】
     * @return 【更新后的任务实体】
     */
    @PostMapping("/{id}/pause")
    Object pause(HttpServletRequest request, @PathVariable Long id) {
        return tasks.pause(current(request), id);
    }

    /**
     * 【恢复暂停的任务，回到 DOING 状态】
     *
     * @param request 【HTTP 请求】
     * @param id      【任务 ID】
     * @return 【更新后的任务实体】
     */
    @PostMapping("/{id}/resume")
    Object resume(HttpServletRequest request, @PathVariable Long id) {
        return tasks.resume(current(request), id);
    }

    /**
     * 【提交任务学习证明，触发 AI 审核流程】
     *
     * 【提交流程：校验任务状态 → 内容安全审核 → 持久化提交记录 →
     * 异步触发 AI 审核 → 返回即时响应（含 task、submission、review、aiQuestion）】
     *
     * @param request 【HTTP 请求】
     * @param id      【任务 ID】
     * @param body    【提交请求体，包含文字证明、截图、代码片段等】
     * @return 【包含任务、提交记录和审核结果的 Map】
     */
    @PostMapping("/{id}/submit")
    Object submit(HttpServletRequest request, @PathVariable Long id, @RequestBody SubmitTaskRequest body) {
        return tasks.submit(current(request), id, body);
    }
}
