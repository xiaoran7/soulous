package com.soulous.timetable;

import com.soulous.auth.UserService;
import com.soulous.common.ratelimit.RateLimit;
import com.soulous.common.web.BaseController;
import com.soulous.timetable.TimetableDtos.CourseView;
import com.soulous.timetable.TimetableDtos.SyncRequest;
import com.soulous.timetable.TimetableDtos.SyncResult;
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
     * 【同步课表：输入教务系统账号密码爬取获取数据。
     *  爬虫涉及教务登录，限流每小时 10 次、每天 50 次以防频繁重试或滥用。】
     */
    @PostMapping("/sync")
    @RateLimit(name = "timetable-sync-hourly", capacity = 10, refillTokens = 10, refillPeriod = 1,
            refillUnit = TimeUnit.HOURS, key = RateLimit.KeyType.USER)
    @RateLimit(name = "timetable-sync-daily", capacity = 50, refillTokens = 50, refillPeriod = 1,
            refillUnit = TimeUnit.DAYS, key = RateLimit.KeyType.USER)
    SyncResult syncTimetable(HttpServletRequest request, @Valid @RequestBody SyncRequest body) {
        return service.syncFromCrawler(current(request), body);
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
