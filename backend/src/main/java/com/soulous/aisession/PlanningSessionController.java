package com.soulous.aisession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulous.auth.UserService;
import com.soulous.common.ratelimit.RateLimit;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 【AI 规划会话 REST 控制器：对外暴露 AI 规划对话的全套 HTTP 接口】
 *
 * <p>提供新建目标、打卡跟进、发送消息（普通/SSE 流式）、编辑/删除计划任务、
 * 确认计划、放弃会话、查询会话列表等端点。所有接口挂在 /api/ai/sessions 路径下，
 * 需要登录态（通过 BaseController.current() 获取当前用户）。
 * 涉及 LLM 调用的端点均配置了小时级和天级双重限流，防止滥用。</p>
 */
@RestController
@RequestMapping("/api/ai/sessions")
class PlanningSessionController extends BaseController {
    private final PlanningSessionService service;

    /**
     * 【构造函数：注入用户服务和规划会话服务】
     *
     * @param users   【用户服务，用于 BaseController 中获取当前登录用户】
     * @param service 【规划会话业务服务】
     */
    PlanningSessionController(UserService users, PlanningSessionService service) {
        super(users);
        this.service = service;
    }

    /**
     * 【获取活跃目标列表（含进度）：返回当前用户所有 ACTIVE 状态目标及其任务完成情况】
     *
     * @param request 【HTTP 请求，用于提取当前用户】
     * @return 【目标信息列表，每项包含 id、title、status、totalTasks、completedTasks 等字段】
     */
    @GetMapping("/active-goals")
    List<Map<String, Object>> activeGoals(HttpServletRequest request) {
        return service.activeGoalsWithProgress(current(request));
    }

    /**
     * 【获取全部目标列表（含进度）：返回当前用户所有目标（含已完成/已归档）及其任务进度】
     *
     * @param request 【HTTP 请求】
     * @return 【全部目标信息列表】
     */
    @GetMapping("/goals")
    List<Map<String, Object>> allGoals(HttpServletRequest request) {
        return service.allGoalsWithProgress(current(request));
    }

    /**
     * 【新建目标并开启规划会话：用户提交新学习目标，AI 开始引导拆解】
     *
     * @param request 【HTTP 请求】
     * @param body    【请求体，包含 goal 目标描述】
     * @return 【会话视图，包含 AI 的首轮回复和可能的重复目标提示】
     */
    @PostMapping("/new-goal")
    SessionDtos.SessionView startNewGoal(HttpServletRequest request,
                                         @Valid @RequestBody SessionDtos.StartNewGoalRequest body) {
        return service.startNewGoal(current(request), body.goal());
    }

    /**
     * 【发起打卡跟进：对已有目标开启 check-in 会话，AI 基于进展给出建议】
     *
     * @param request 【HTTP 请求】
     * @param body    【请求体，包含 goalId 目标 ID】
     * @return 【会话视图】
     */
    @PostMapping("/check-in")
    SessionDtos.SessionView startCheckIn(HttpServletRequest request,
                                         @RequestBody SessionDtos.StartCheckInRequest body) {
        return service.startCheckIn(current(request), body.goalId());
    }

    /**
     * 【获取单个会话详情：返回指定会话的完整信息和全部对话轮次】
     *
     * @param request 【HTTP 请求】
     * @param id      【会话 ID 路径参数】
     * @return 【会话视图】
     */
    @GetMapping("/{id}")
    SessionDtos.SessionView get(HttpServletRequest request, @PathVariable Long id) {
        return service.get(current(request), id);
    }

    /**
     * 【发送消息（非流式）：用户在会话中发送一条消息，等待 AI 完整回复后返回】
     *
     * <p>配置了小时级（60 次/小时）和天级（200 次/天）双重限流。</p>
     *
     * @param request 【HTTP 请求】
     * @param id      【会话 ID】
     * @param body    【消息内容】
     * @return 【更新后的会话视图，包含 AI 回复】
     */
    @PostMapping("/{id}/messages")
    @RateLimit(name = "ai-hourly", capacity = 60, refillTokens = 60, refillPeriod = 1,
            refillUnit = java.util.concurrent.TimeUnit.HOURS, key = RateLimit.KeyType.USER)
    @RateLimit(name = "ai-daily", capacity = 200, refillTokens = 200, refillPeriod = 1,
            refillUnit = java.util.concurrent.TimeUnit.DAYS, key = RateLimit.KeyType.USER)
    SessionDtos.SessionView postMessage(HttpServletRequest request, @PathVariable Long id,
                                        @Valid @RequestBody SessionDtos.MessageRequest body) {
        return service.postMessage(current(request), id, body.content());
    }

    /**
     * 【发送消息（SSE 流式）：用户发送消息后以 Server-Sent Events 逐 token 接收 AI 回复】
     *
     * <p>Streaming variant of postMessage. Returns SSE-formatted events:
     *   event: token, data: "&lt;text chunk&gt;"      — fired for each incremental piece
     *   event: done,  data: &lt;SessionView JSON&gt;  — fired once after stream completes
     *   event: error, data: "&lt;message&gt;"         — fired if anything blew up</p>
     *
     * <p>Same rate limits as the non-stream sibling. Disables proxy buffering via
     * X-Accel-Buffering: no so nginx doesn't hold tokens until the stream ends.</p>
     */
    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper();

    /**
     * 【SSE 流式消息端点：以 text/event-stream 格式返回逐 token 的 AI 回复】
     *
     * @param request  【HTTP 请求】
     * @param response 【HTTP 响应，用于设置 SSE 头部】
     * @param id       【会话 ID】
     * @param body     【消息内容】
     * @return 【流式响应体，内部逐块写入 SSE 事件】
     */
    @PostMapping(path = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(name = "ai-hourly", capacity = 60, refillTokens = 60, refillPeriod = 1,
            refillUnit = java.util.concurrent.TimeUnit.HOURS, key = RateLimit.KeyType.USER)
    @RateLimit(name = "ai-daily", capacity = 200, refillTokens = 200, refillPeriod = 1,
            refillUnit = java.util.concurrent.TimeUnit.DAYS, key = RateLimit.KeyType.USER)
    StreamingResponseBody postMessageStream(HttpServletRequest request, HttpServletResponse response,
                                            @PathVariable Long id,
                                            @Valid @RequestBody SessionDtos.MessageRequest body) {
        var user = current(request);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        var content = body.content();
        return out -> {
            try {
                var view = service.postMessageStream(user, id, content, chunk -> {
                    try {
                        var data = STREAM_MAPPER.writeValueAsString(chunk);
                        out.write(("event: token\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception ignored) {
                        // Client likely disconnected — we still let the service finish so the
                        // turn gets persisted, but stop trying to push to a dead socket.
                    }
                });
                var payload = STREAM_MAPPER.writeValueAsString(view);
                out.write(("event: done\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ex) {
                try {
                    var data = STREAM_MAPPER.writeValueAsString(ex.getMessage() == null ? "stream failed" : ex.getMessage());
                    out.write(("event: error\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) { /* nothing we can do here */ }
            }
        };
    }

    /**
     * 【编辑计划任务：用户在 PLAN_PROPOSED 状态下修改待确认计划中的某个任务字段】
     *
     * @param request 【HTTP 请求】
     * @param id      【会话 ID】
     * @param index   【任务在计划数组中的索引】
     * @param body    【补丁内容，仅非 null 字段生效】
     * @return 【更新后的会话视图】
     */
    @PatchMapping("/{id}/plan/tasks/{index}")
    SessionDtos.SessionView editPlanTask(HttpServletRequest request, @PathVariable Long id,
                                         @PathVariable int index,
                                         @RequestBody SessionDtos.EditPlanTaskRequest body) {
        return service.editPlanTask(current(request), id, index, body);
    }

    /**
     * 【删除计划任务：用户在 PLAN_PROPOSED 状态下移除待确认计划中的某个任务】
     *
     * @param request 【HTTP 请求】
     * @param id      【会话 ID】
     * @param index   【任务在计划数组中的索引】
     * @return 【更新后的会话视图】
     */
    @DeleteMapping("/{id}/plan/tasks/{index}")
    SessionDtos.SessionView deletePlanTask(HttpServletRequest request, @PathVariable Long id,
                                           @PathVariable int index) {
        return service.deletePlanTask(current(request), id, index);
    }

    /**
     * 【确认计划：用户确认 AI 提出的 PLAN_JSON 草案，任务持久化到数据库】
     *
     * @param request 【HTTP 请求】
     * @param id      【会话 ID】
     * @return 【已提交状态的会话视图】
     */
    @PostMapping("/{id}/commit")
    SessionDtos.SessionView commit(HttpServletRequest request, @PathVariable Long id) {
        return service.commitPlan(current(request), id);
    }

    /**
     * 【放弃会话：用户主动放弃当前规划会话，不产生任何任务】
     *
     * @param request 【HTTP 请求】
     * @param id      【会话 ID】
     * @return 【已放弃状态的会话视图】
     */
    @PostMapping("/{id}/abandon")
    SessionDtos.SessionView abandon(HttpServletRequest request, @PathVariable Long id) {
        return service.abandon(current(request), id);
    }

    /**
     * 【查询目标下的会话列表：返回指定目标关联的全部会话摘要】
     *
     * @param request 【HTTP 请求】
     * @param goalId  【目标 ID 查询参数】
     * @return 【会话摘要列表】
     */
    @GetMapping
    List<SessionDtos.SessionSummary> listForGoal(HttpServletRequest request, @RequestParam Long goalId) {
        return service.listForGoal(current(request), goalId);
    }

    /**
     * 【删除会话：删除非 COMMITTED 状态的会话及其全部对话轮次】
     *
     * @param request 【HTTP 请求】
     * @param id      【会话 ID】
     * @return 【删除结果，包含被删除的轮次数】
     */
    @DeleteMapping("/{id}")
    SessionDtos.DeleteSessionResult delete(HttpServletRequest request, @PathVariable Long id) {
        return service.deleteSession(current(request), id);
    }
}
