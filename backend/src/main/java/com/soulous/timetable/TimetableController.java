package com.soulous.timetable;

import com.soulous.auth.UserService;
import com.soulous.common.ratelimit.RateLimit;
import com.soulous.common.web.BaseController;
import com.soulous.timetable.TimetableDtos.CourseView;
import com.soulous.timetable.TimetableDtos.ImportRequest;
import com.soulous.timetable.TimetableDtos.ImportResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 【课表 REST 控制器：导入、查询、手动增删课表。
 *  全部接口需登录。导入会调用 LLM 解析，施加按用户限流防滥用。】
 */
@RestController
@RequestMapping("/api/timetable")
class TimetableController extends BaseController {
    private final TimetableService service;

    TimetableController(UserService users, TimetableService service) {
        super(users);
        this.service = service;
    }

    /** 【列出当前用户课表，可选 ?semester= 过滤】 */
    @GetMapping
    List<CourseView> list(HttpServletRequest request, @RequestParam(required = false) String semester) {
        return service.list(current(request), semester);
    }

    /**
     * 【导入课表：粘贴教务系统 HTML，AI 解析后落库。
     *  LLM 较重，限流每小时 30 次、每天 100 次（按用户）。】
     */
    @PostMapping("/import")
    @RateLimit(name = "timetable-import-hourly", capacity = 30, refillTokens = 30, refillPeriod = 1,
            refillUnit = TimeUnit.HOURS, key = RateLimit.KeyType.USER)
    @RateLimit(name = "timetable-import-daily", capacity = 100, refillTokens = 100, refillPeriod = 1,
            refillUnit = TimeUnit.DAYS, key = RateLimit.KeyType.USER)
    ImportResult importTimetable(HttpServletRequest request, @Valid @RequestBody ImportRequest body) {
        return service.importHtml(current(request), body);
    }

    /** 【手动新增一节课（不走 LLM，无需限流）】 */
    @PostMapping
    CourseView create(HttpServletRequest request, @Valid @RequestBody TimetableDtos.CourseCreateRequest body) {
        return service.create(current(request), body);
    }

    /** 【删除一条课程】 */
    @DeleteMapping("/{id}")
    Map<String, Object> delete(HttpServletRequest request, @PathVariable Long id) {
        service.delete(current(request), id);
        return Map.of("deleted", true, "id", id);
    }

    /** 【清空课表，可选 ?semester= 只清某学期】 */
    @DeleteMapping
    Map<String, Object> clear(HttpServletRequest request, @RequestParam(required = false) String semester) {
        int removed = service.clear(current(request), semester);
        return Map.of("cleared", removed);
    }
}
