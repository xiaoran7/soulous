package com.soulous.focus;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 【专注控制器：处理所有与专注计时相关的 HTTP 请求，包括查询会话列表、
 *  获取当前活跃会话、开始新会话、暂停、恢复、完成等操作。
 *  继承 BaseController 获取当前用户认证能力。】
 */
@RestController
@RequestMapping("/api/focus")
class FocusController extends BaseController {
    /** 【专注业务逻辑服务】 */
    private final FocusService focus;

    /**
     * 【构造注入：通过 Spring 依赖注入用户服务和专注服务。】
     */
    FocusController(UserService users, FocusService focus) {
        super(users);
        this.focus = focus;
    }

    /**
     * 【获取当前用户的所有专注会话列表，按创建时间倒序排列。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @return 【专注会话列表】
     */
    @GetMapping("/sessions")
    Object list(HttpServletRequest request) {
        return focus.list(current(request));
    }

    /**
     * 【获取当前用户活跃的专注会话（RUNNING 或 PAUSED 状态）。
     *  如果没有活跃会话，返回空 Map 而非 null，便于前端统一处理。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @return 【活跃的专注会话，无活跃会话时返回空 Map】
     */
    @GetMapping("/active")
    Object active(HttpServletRequest request) {
        var session = focus.active(current(request));
        return session != null ? session : Map.of();
    }

    /**
     * 【开始新的专注会话：创建一个专注计时会话并返回。
     *  请求体包含标题（必填）、计划时长（可选，默认25分钟）、关联任务 ID（可选）。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @param body 【专注会话请求体】
     * @return 【新创建的专注会话】
     */
    @PostMapping("/sessions")
    Object start(HttpServletRequest request, @RequestBody FocusSessionRequest body) {
        return focus.start(current(request), body);
    }

    /**
     * 【暂停专注会话：冻结计时器，暂停期间不计入已用时间。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @param id 【专注会话 ID】
     * @return 【暂停后的专注会话】
     */
    @PostMapping("/sessions/{id}/pause")
    Object pause(HttpServletRequest request, @PathVariable Long id) {
        return focus.pause(current(request), id);
    }

    /**
     * 【恢复专注会话：重新启动计时器。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @param id 【专注会话 ID】
     * @return 【恢复后的专注会话】
     */
    @PostMapping("/sessions/{id}/resume")
    Object resume(HttpServletRequest request, @PathVariable Long id) {
        return focus.resume(current(request), id);
    }

    /**
     * 【完成/中止专注会话：根据请求体中的 outcome 字段决定是正常完成还是中止。
     *  正常完成会发放经验奖励并更新关联任务的用时。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @param id 【专注会话 ID】
     * @param body 【完成请求体，outcome 为 "aborted" 表示中止】
     * @return 【完成后的专注会话】
     */
    @PostMapping("/sessions/{id}/finish")
    Object finish(HttpServletRequest request, @PathVariable Long id, @RequestBody FocusFinishRequest body) {
        return focus.finish(current(request), id, body);
    }
}
