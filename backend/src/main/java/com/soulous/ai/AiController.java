package com.soulous.ai;

import com.soulous.auth.UserService;
import com.soulous.common.ratelimit.RateLimit;
import com.soulous.common.web.BaseController;
import com.soulous.task.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 【AI 功能的 REST 控制器，提供任务分解、审阅通知、问答等 AI 相关接口。
 * 继承 BaseController 获取当前用户上下文，所有接口均需登录。
 * 对高频操作（分解、问答）施加了双重限流（每小时 + 每天），防止滥用。】
 */
@RestController
@RequestMapping("/api/ai")
class AiController extends BaseController {
    private final AiService ai;
    private final TaskService tasks;
    private final LlmService llm;

    /**
     * 【构造注入：注入 AI 服务、任务服务和 LLM 服务。
     * 通过 super(users) 将 UserService 传递给 BaseController 以支持用户鉴权。】
     */
    AiController(UserService users, AiService ai, TaskService tasks, LlmService llm) {
        super(users);
        this.ai = ai;
        this.tasks = tasks;
        this.llm = llm;
    }

    /**
     * 【获取当前 AI 配置信息（provider、model 等），供前端展示或调试使用。
     * 需要登录但无限流限制。】
     *
     * @param request 【HTTP 请求，用于提取当前登录用户】
     * @return 【LLM 服务的配置信息对象】
     */
    @GetMapping("/info")
    Object aiInfo(HttpServletRequest request) {
        current(request);
        return llm.info();
    }

    /**
     * 【AI 任务分解接口：接收用户输入的目标（goal），调用 LLM 将其分解为多个子任务。
     * 施加双重限流——每小时 60 次、每天 200 次，按用户维度限流。
     * 请求体需通过 @Valid 校验（DecposeRequest 内部有字段约束）。】
     *
     * @param request 【HTTP 请求，用于提取当前登录用户】
     * @param body    【分解请求体，包含用户输入的目标文本】
     * @return 【分解结果，包含生成的子任务列表】
     */
    @PostMapping("/decompose")
    @RateLimit(name = "ai-hourly", capacity = 60, refillTokens = 60, refillPeriod = 1,
            refillUnit = java.util.concurrent.TimeUnit.HOURS, key = RateLimit.KeyType.USER)
    @RateLimit(name = "ai-daily", capacity = 200, refillTokens = 200, refillPeriod = 1,
            refillUnit = java.util.concurrent.TimeUnit.DAYS, key = RateLimit.KeyType.USER)
    Object decompose(HttpServletRequest request, @Valid @RequestBody DecomposeRequest body) {
        var user = current(request);
        return ai.decompose(user, body.goal());
    }

    /**
     * 【AI 审阅通知接口：告知前端 AI 审阅是自动触发的，无需手动调用。
     * 实际的审阅逻辑在任务提交时由 AiService 自动执行。】
     *
     * @param request 【HTTP 请求，用于验证用户登录状态】
     * @return 【提示信息 map，说明 AI 审阅在任务提交时自动触发】
     */
    @PostMapping("/review")
    Object reviewNotice(HttpServletRequest request) {
        current(request);
        return Map.of("message", "AI review is triggered automatically when a task is submitted.");
    }

    /**
     * 【AI 问答接口：当 AI 审阅后提出追问时，用户通过此接口提交回答。
     * 同样施加双重限流，防止恶意刷请求。】
     *
     * @param request 【HTTP 请求，用于提取当前登录用户】
     * @param body    【问答请求体，包含 submissionId 和用户的 answer】
     * @return 【问答处理结果】
     */
    @PostMapping("/question/answer")
    @RateLimit(name = "ai-hourly", capacity = 60, refillTokens = 60, refillPeriod = 1,
            refillUnit = java.util.concurrent.TimeUnit.HOURS, key = RateLimit.KeyType.USER)
    @RateLimit(name = "ai-daily", capacity = 200, refillTokens = 200, refillPeriod = 1,
            refillUnit = java.util.concurrent.TimeUnit.DAYS, key = RateLimit.KeyType.USER)
    Object answerQuestion(HttpServletRequest request, @RequestBody AiAnswerRequest body) {
        return tasks.answerAiQuestion(current(request), body.submissionId(), body.answer());
    }
}
