package com.soulous.stats;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【学习统计模块的 REST 控制器，提供用户学习数据统计接口。
 * 继承 BaseController 获取当前用户认证能力。】
 */
@RestController
@RequestMapping("/api/stats")
class StatsController extends BaseController {
    private final StatsService stats;

    StatsController(UserService users, StatsService stats) {
        super(users);
        this.stats = stats;
    }

    /**
     * 【获取当前用户的学习统计摘要接口。
     * 返回今日任务数、经验值、学习时长、完成率、趋势、
     * 课程分布、通过率、连续学习天数、专注统计等综合数据。】
     *
     * @param request 【HTTP 请求对象，用于获取当前用户身份】
     * @return 【包含各项学习统计指标的 Map】
     */
    @GetMapping("/summary")
    Object summary(HttpServletRequest request) {
        return stats.summary(current(request));
    }
}
