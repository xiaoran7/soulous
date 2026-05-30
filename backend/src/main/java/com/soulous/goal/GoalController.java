package com.soulous.goal;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【目标控制器：处理与学习目标相关的 HTTP 请求，包括查看目标详情、更新目标、删除目标等操作。
 *  继承 BaseController 获取当前用户认证能力。
 *  目标是用户设定的学习方向，关联多个学习任务和 AI 规划会话。】
 */
@RestController
@RequestMapping("/api/goals")
class GoalController extends BaseController {
    /** 【目标业务逻辑服务】 */
    private final GoalService service;

    /**
     * 【构造注入：通过 Spring 依赖注入用户服务和目标服务。】
     */
    GoalController(UserService users, GoalService service) {
        super(users);
        this.service = service;
    }

    /**
     * 【获取目标详情：返回目标的完整信息，包括关联任务的统计数据（总数、已完成、进行中）。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @param id 【目标 ID】
     * @return 【包含目标详情和任务统计的 Map】
     */
    @GetMapping("/{id}")
    Map<String, Object> detail(HttpServletRequest request, @PathVariable Long id) {
        return service.detail(current(request), id);
    }

    /**
     * 【更新目标：支持修改标题、目标日期、状态等字段。
     *  使用 @Valid 进行请求体校验。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @param id 【目标 ID】
     * @param body 【更新请求体，包含可选的 title、targetDate、status、clearTargetDate 字段】
     * @return 【更新后的目标实体】
     */
    @PatchMapping("/{id}")
    Goal update(HttpServletRequest request, @PathVariable Long id,
                @Valid @RequestBody GoalDtos.UpdateGoalRequest body) {
        return service.update(current(request), id, body);
    }

    /**
     * 【硬删除目标：级联解除关联任务的绑定，删除关联的 AI 规划会话及其对话记录。
     *  返回删除结果统计（解除绑定的任务数、关闭的会话数）。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @param id 【目标 ID】
     * @return 【删除结果，包含 ID、解除绑定的任务数、关闭的会话数】
     */
    @DeleteMapping("/{id}")
    GoalDtos.DeleteResult delete(HttpServletRequest request, @PathVariable Long id) {
        return service.hardDelete(current(request), id);
    }
}
