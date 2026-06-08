package com.soulous.companion;

import com.soulous.auth.UserService;
import com.soulous.common.ratelimit.RateLimit;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 【陪伴宠物 REST 控制器：/api/companion】
 *
 * <p>一个全新的产品表面 —— 有记忆、会陪伴的宠物，大脑跑在独立的 Anima agent 服务里。
 * 与「拆学习计划」的 {@code /api/chat} 完全独立，不共享对话/计划协议。</p>
 */
@RestController
@RequestMapping("/api/companion")
class CompanionController extends BaseController {
    private final CompanionService service;

    CompanionController(UserService users, CompanionService service) {
        super(users);
        this.service = service;
    }

    @PostMapping("/chat")
    @RateLimit(name = "ai-hourly", capacity = 60, refillTokens = 60, refillPeriod = 1,
            refillUnit = TimeUnit.HOURS, key = RateLimit.KeyType.USER)
    @RateLimit(name = "ai-daily", capacity = 200, refillTokens = 200, refillPeriod = 1,
            refillUnit = TimeUnit.DAYS, key = RateLimit.KeyType.USER)
    CompanionDtos.ChatReply chat(HttpServletRequest request,
                                 @Valid @RequestBody CompanionDtos.ChatRequest body) {
        return new CompanionDtos.ChatReply(service.chat(current(request), body.message()));
    }

    @GetMapping("/history")
    CompanionDtos.History history(HttpServletRequest request) {
        return new CompanionDtos.History(service.history(current(request)));
    }

    @GetMapping("/memory")
    CompanionDtos.Memory memory(HttpServletRequest request) {
        return new CompanionDtos.Memory(service.memory(current(request)));
    }
}
