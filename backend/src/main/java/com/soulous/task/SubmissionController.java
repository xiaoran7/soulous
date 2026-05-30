package com.soulous.task;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【任务提交控制器——提供任务提交记录的 RESTful 查询接口。
 *  继承 BaseController 获取用户认证能力，通过委托 TaskService 完成业务逻辑。
 *  目前提供两个只读端点：查看当前用户的提交列表、查看单条提交详情。
 *  提交的创建（POST）由 TaskController 中的 submit 方法处理，不在此控制器中。】
 *
 * <p>English: REST controller that exposes read-only endpoints for task submissions.</p>
 */
@RestController
@RequestMapping("/api/submissions")
class SubmissionController extends BaseController {

    /** 【任务服务——处理提交记录查询的核心业务逻辑】 */
    private final TaskService tasks;

    /**
     * 【构造函数——注入用户服务和任务服务依赖。】
     *
     * @param users 【用户服务，用于从请求中解析当前登录用户（传给 BaseController）】
     * @param tasks 【任务服务，提供提交记录查询方法】
     */
    SubmissionController(UserService users, TaskService tasks) {
        super(users);
        this.tasks = tasks;
    }

    /**
     * 【获取当前用户的所有提交记录。
     *  GET /api/submissions/my
     *  通过 BaseController.current() 从请求中解析当前用户，再调用 TaskService 查询。】
     *
     * @param request 【HTTP 请求对象，用于提取认证信息以识别当前用户】
     * @return 【当前用户的提交记录列表，JSON 格式返回给前端】
     */
    @GetMapping("/my")
    Object mine(HttpServletRequest request) {
        return tasks.submissions(current(request));
    }

    /**
     * 【获取指定提交记录的详情。
     *  GET /api/submissions/{id}
     *  包含提交内容、审核状态、关联任务等完整信息。】
     *
     * @param request 【HTTP 请求对象，用于提取认证信息以识别当前用户】
     * @param id      【提交记录 ID，从 URL 路径中提取】
     * @return 【提交详情对象，JSON 格式返回给前端】
     */
    @GetMapping("/{id}")
    Object detail(HttpServletRequest request, @PathVariable Long id) {
        return tasks.submissionDetail(current(request), id);
    }
}
