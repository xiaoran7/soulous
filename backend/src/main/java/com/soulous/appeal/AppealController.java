package com.soulous.appeal;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【申诉模块的 REST 控制器，处理用户申诉相关的 HTTP 请求。
 * 提供创建申诉和查询个人申诉列表两个接口。
 * 继承 BaseController 获取当前用户认证能力。】
 */
@RestController
@RequestMapping("/api/appeals")
class AppealController extends BaseController {
    private final AppealService appeals;

    AppealController(UserService users, AppealService appeals) {
        super(users);
        this.appeals = appeals;
    }

    /**
     * 【创建申诉接口。
     * 接收 POST 请求，从请求体中解析申诉信息，
     * 调用 AppealService.create 创建申诉记录。】
     *
     * @param request 【HTTP 请求对象，用于获取当前用户身份】
     * @param body    【申诉请求体，包含 submissionId、appealReason、screenshotUrls】
     * @return 【创建成功的 Appeal 实体】
     */
    @PostMapping
    Object create(HttpServletRequest request, @RequestBody AppealRequest body) {
        return appeals.create(current(request), body);
    }

    /**
     * 【查询当前用户的申诉列表接口。
     * 接收 GET 请求，返回当前用户的所有申诉记录，按创建时间倒序排列。】
     *
     * @param request 【HTTP 请求对象，用于获取当前用户身份】
     * @return 【当前用户的申诉记录列表】
     */
    @GetMapping("/my")
    Object mine(HttpServletRequest request) {
        return appeals.mine(current(request));
    }
}
