package com.soulous.agent;

import com.soulous.auth.UserAccount;
import com.soulous.auth.UserRepository;
import com.soulous.checkin.CheckinRepository;
import com.soulous.moderation.ModerationProperties;
import com.soulous.pet.PetRepository;
import com.soulous.task.StudyRecordRepository;
import com.soulous.timetable.CourseEntryRepository;
import com.soulous.timetable.ExamEntryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 【内网端点：仅供 agent-service 回调（业务工具 + guardrail fast-path）】
 *
 * <p>鉴权：X-Service-Token 必须与 soulous.agent.token 一致（在 SecurityConfig 中
 * /internal/** 已放行 JWT，由本控制器自行校验 service token）。
 * agent 工具携带的 userId 由 agent 从请求上下文注入，LLM 无法伪造他人身份。</p>
 */
@RestController
@RequestMapping("/internal")
public class InternalAgentController {

    private final AgentProperties props;
    private final ModerationProperties moderationProps;
    private final UserRepository users;
    private final StudyRecordRepository records;
    private final CourseEntryRepository courses;
    private final ExamEntryRepository exams;
    private final PetRepository pets;
    private final CheckinRepository checkins;

    public InternalAgentController(AgentProperties props, ModerationProperties moderationProps,
                                   UserRepository users, StudyRecordRepository records,
                                   CourseEntryRepository courses, ExamEntryRepository exams,
                                   PetRepository pets, CheckinRepository checkins) {
        this.props = props;
        this.moderationProps = moderationProps;
        this.users = users;
        this.records = records;
        this.courses = courses;
        this.exams = exams;
        this.pets = pets;
        this.checkins = checkins;
    }

    private boolean badToken(String token) {
        var expected = props.getToken();
        return expected == null || expected.isBlank() || !expected.equals(token);
    }

    /** 【内容审核 fast-path：仅跑正则快速拦截规则，毫秒级，供 agent guardrail 使用】 */
    @PostMapping("/moderation/check")
    public ResponseEntity<Map<String, Object>> moderationCheck(
            @RequestHeader(value = "X-Service-Token", required = false) String token,
            @RequestBody Map<String, String> body) {
        if (badToken(token)) return ResponseEntity.status(401).build();
        var text = body.getOrDefault("text", "");
        var blocked = false;
        for (var pattern : moderationProps.getFastBlockPatterns()) {
            try {
                if (Pattern.compile(pattern).matcher(text).find()) {
                    blocked = true;
                    break;
                }
            } catch (Exception ignored) {
                // 无效正则跳过，与 ModerationService fast-path 行为一致
            }
        }
        return ResponseEntity.ok(Map.of("blocked", blocked));
    }

    /** 【近 7 天专注学习时长：逐日分钟数 + 合计】 */
    @GetMapping("/agent/focus-history")
    public ResponseEntity<Map<String, Object>> focusHistory(
            @RequestHeader(value = "X-Service-Token", required = false) String token,
            @RequestParam Long userId) {
        if (badToken(token)) return ResponseEntity.status(401).build();
        var user = users.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        var since = LocalDate.now().minusDays(6).atStartOfDay();
        var perDay = new LinkedHashMap<String, Integer>();
        for (int i = 6; i >= 0; i--) perDay.put(LocalDate.now().minusDays(i).toString(), 0);
        int total = 0;
        for (var r : records.findByUserAndCreatedAtAfter(user, since)) {
            if (r.createdAt == null || r.studyMinutes == null) continue;
            var day = r.createdAt.toLocalDate().toString();
            perDay.merge(day, r.studyMinutes, Integer::sum);
            total += r.studyMinutes;
        }
        return ResponseEntity.ok(Map.of("days", perDay, "totalMinutes", total));
    }

    /** 【本周课表 + 近期考试安排（精简字段）】 */
    @GetMapping("/agent/timetable")
    public ResponseEntity<Map<String, Object>> timetable(
            @RequestHeader(value = "X-Service-Token", required = false) String token,
            @RequestParam Long userId) {
        if (badToken(token)) return ResponseEntity.status(401).build();
        var user = users.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        var courseList = new ArrayList<Map<String, Object>>();
        for (var c : courses.findByUserOrderByDayOfWeekAscStartSectionAsc(user)) {
            var m = new LinkedHashMap<String, Object>();
            m.put("courseName", c.courseName);
            m.put("dayOfWeek", c.dayOfWeek);
            m.put("startSection", c.startSection);
            m.put("endSection", c.endSection);
            m.put("location", c.location);
            courseList.add(m);
            if (courseList.size() >= 40) break;
        }
        var examList = new ArrayList<Map<String, Object>>();
        for (var e : exams.findByUserOrderBySemesterDescExamTimeAsc(user)) {
            var m = new LinkedHashMap<String, Object>();
            m.put("courseName", e.courseName);
            m.put("examTime", e.examTime);
            m.put("room", e.room);
            examList.add(m);
            if (examList.size() >= 10) break;
        }
        return ResponseEntity.ok(Map.of("courses", courseList, "exams", examList));
    }

    /** 【出战宠物状态 + 连续打卡天数】 */
    @GetMapping("/agent/pet-status")
    public ResponseEntity<Map<String, Object>> petStatus(
            @RequestHeader(value = "X-Service-Token", required = false) String token,
            @RequestParam Long userId) {
        if (badToken(token)) return ResponseEntity.status(401).build();
        var user = users.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        var out = new LinkedHashMap<String, Object>();
        pets.findByUserAndActiveTrue(user).ifPresent(pet -> {
            out.put("name", pet.name);
            out.put("level", pet.level);
            out.put("mood", pet.mood);
            out.put("status", pet.status);
            out.put("growthStage", pet.growthStage);
        });
        out.put("streak", streakOf(user));
        return ResponseEntity.ok(out);
    }

    private int streakOf(UserAccount user) {
        return checkins.findTopByUserOrderByCheckinDateDesc(user)
                .filter(c -> !c.checkinDate.isBefore(LocalDate.now().minusDays(1)))
                .map(c -> c.streak)
                .orElse(0);
    }
}
