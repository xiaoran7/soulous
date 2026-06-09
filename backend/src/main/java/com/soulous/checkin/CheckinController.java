package com.soulous.checkin;

import com.soulous.auth.UserService;
import com.soulous.checkin.CheckinDtos.CheckinResult;
import com.soulous.checkin.CheckinDtos.CheckinStatus;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【每日打卡 REST 控制器：查询今日打卡状态、执行打卡领奖。需登录。】
 */
@RestController
@RequestMapping("/api/checkin")
class CheckinController extends BaseController {
    private final CheckinService service;

    CheckinController(UserService users, CheckinService service) {
        super(users);
        this.service = service;
    }

    /** 【今日打卡状态】 */
    @GetMapping
    CheckinStatus status(HttpServletRequest request) {
        return service.status(current(request));
    }

    /** 【执行每日打卡（幂等）】 */
    @PostMapping
    CheckinResult checkin(HttpServletRequest request) {
        return service.checkin(current(request));
    }
}
