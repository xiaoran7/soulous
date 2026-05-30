package com.soulous.timetable;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulous.ai.LlmService;
import com.soulous.auth.UserAccount;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.timetable.TimetableDtos.CourseView;
import com.soulous.timetable.TimetableDtos.ImportRequest;
import com.soulous.timetable.TimetableDtos.ImportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 【课表业务服务：把"用户上传/粘贴的教务系统课表（HTML）"变成结构化课表，并支持手动增删。
 *
 * <p>核心链路 {@link #importHtml} —— HTML → 清洗 → LLM(DeepSeek) 解析为 JSON → 落库（可覆盖），
 * 复用 {@link LlmService#completeJsonValidated} 的自纠重试 + JSON 提取能力，
 * LLM 不可用时抛 {@link BadRequestException}，由前端提示用户。
 * 课表数据另作为背景画像喂给 AI 拆解（见 PlanningSessionService 的 [COURSES] 段）。</p>
 */
@Service
public class TimetableService {
    private static final Logger log = LoggerFactory.getLogger(TimetableService.class);

    /** 【送给 LLM 的 HTML 最大长度，超出截断，防止超大页面烧 token / 超上下文】 */
    private static final int MAX_HTML_CHARS = 48_000;

    private final CourseEntryRepository repo;
    private final LlmService llm;

    public TimetableService(CourseEntryRepository repo, LlmService llm) {
        this.repo = repo;
        this.llm = llm;
    }

    // ===================== 导入 =====================

    /**
     * 【导入课表：清洗 HTML → LLM 解析 → 落库。
     *  replace=true 时先按学期（无学期则整表）清空旧数据再写。】
     *
     * @throws BadRequestException HTML 为空、AI 不可用或解析不出任何课程时
     */
    @Transactional
    public ImportResult importHtml(UserAccount user, ImportRequest req) {
        if (req == null || req.html() == null || req.html().isBlank()) {
            throw new BadRequestException("课表内容为空，请粘贴教务系统课表页的 HTML。");
        }
        if (!llm.isAvailable()) {
            throw new BadRequestException("AI 解析服务当前不可用，请稍后再试或联系管理员检查 LLM 配置。");
        }
        var cleaned = sanitizeHtml(req.html());
        var semester = blankToNull(req.semester());

        var json = llm.completeJsonValidated(
                "u" + user.id, null,
                parseSystemPrompt(),
                "课表 HTML：\n" + cleaned,
                TimetableService::hasUsableCourses
        ).orElseThrow(() -> new BadRequestException(
                "AI 没能从这段内容里解析出课程。请确认粘贴的是课表页的 HTML（含课程表格），而不是登录页或空白页。"));

        var parsed = new ArrayList<CourseEntry>();
        var seen = new java.util.HashSet<String>();
        for (var item : json.path("courses")) {
            var name = safe(item.path("courseName").asText("")).trim();
            if (name.isBlank()) continue;
            int day = clampDay(item.path("dayOfWeek").asInt(0));
            if (day == 0) continue; // 周几无法确定的条目跳过（如底部备注里无固定星期的课）
            // 去重：同一门课在 tooltip/弹层里常重复出现。按 课程名+星期+节次 去重，
            // 同名课在不同天/不同节次（如一周多节高数）是不同条目，不会被误删。
            var dedupKey = name + "|" + day + "|" + optInt(item.path("startSection")) + "|" + optInt(item.path("endSection"));
            if (!seen.add(dedupKey)) continue;
            var c = new CourseEntry();
            c.user = user;
            c.courseName = trunc(name, 200);
            c.teacher = trunc(blankToNull(item.path("teacher").asText(null)), 100);
            c.location = trunc(blankToNull(item.path("location").asText(null)), 120);
            c.dayOfWeek = day;
            c.startSection = optInt(item.path("startSection"));
            c.endSection = optInt(item.path("endSection"));
            c.startTime = trunc(normalizeTime(item.path("startTime").asText(null)), 8);
            c.endTime = trunc(normalizeTime(item.path("endTime").asText(null)), 8);
            c.weeks = trunc(blankToNull(item.path("weeks").asText(null)), 60);
            c.weekParity = parseParity(item.path("weekParity").asText(null));
            c.semester = semester;
            parsed.add(c);
        }
        if (parsed.isEmpty()) {
            throw new BadRequestException("解析结果为空——没有识别到任何有效课程，请检查粘贴内容。");
        }

        if (req.replace()) {
            if (semester != null) repo.deleteByUserAndSemester(user, semester);
            else repo.deleteByUser(user);
        }
        var saved = repo.saveAll(parsed);
        log.info("Timetable imported: user={} semester={} courses={}", user.id, semester, saved.size());
        return new ImportResult(saved.size(), semester, toViews(saved));
    }

    // ===================== 查询 / 删除 =====================

    /** 【列出当前用户课表，semester 为空则返回全部】 */
    @Transactional(readOnly = true)
    public List<CourseView> list(UserAccount user, String semester) {
        var sem = blankToNull(semester);
        var rows = sem == null
                ? repo.findByUserOrderByDayOfWeekAscStartSectionAsc(user)
                : repo.findByUserAndSemesterOrderByDayOfWeekAscStartSectionAsc(user, sem);
        return toViews(rows);
    }

    /**
     * 【手动新增一节课：用户在课表上补一节临时课/活动占用。
     *  courseName/dayOfWeek 必填且校验，其余字段归一化后落库，返回视图。】
     */
    @Transactional
    public CourseView create(UserAccount user, TimetableDtos.CourseCreateRequest req) {
        if (req == null || req.courseName() == null || req.courseName().isBlank()) {
            throw new BadRequestException("课程名称不能为空。");
        }
        int day = clampDay(req.dayOfWeek());
        if (day == 0) {
            throw new BadRequestException("星期无效，应为 1（周一）至 7（周日）。");
        }
        var c = new CourseEntry();
        c.user = user;
        c.courseName = trunc(req.courseName().trim(), 200);
        c.teacher = trunc(blankToNull(req.teacher()), 100);
        c.location = trunc(blankToNull(req.location()), 120);
        c.dayOfWeek = day;
        c.startSection = req.startSection();
        c.endSection = req.endSection();
        c.startTime = trunc(normalizeTime(req.startTime()), 8);
        c.endTime = trunc(normalizeTime(req.endTime()), 8);
        c.weeks = trunc(blankToNull(req.weeks()), 60);
        c.weekParity = parseParity(req.weekParity());
        c.semester = trunc(blankToNull(req.semester()), 40);
        var saved = repo.save(c);
        return CourseView.of(saved);
    }

    /** 【删除一条课程，仅限本人；不存在或越权分别抛 404/403】 */
    @Transactional
    public void delete(UserAccount user, Long id) {
        var c = repo.findById(id).orElseThrow(() -> new NotFoundException("课程不存在"));
        if (c.user == null || !c.user.id.equals(user.id)) {
            throw new ForbiddenException("无权删除该课程");
        }
        repo.delete(c);
    }

    /** 【清空当前用户课表（semester 为空则全清）】 */
    @Transactional
    public int clear(UserAccount user, String semester) {
        var sem = blankToNull(semester);
        var rows = sem == null
                ? repo.findByUserOrderByDayOfWeekAscStartSectionAsc(user)
                : repo.findByUserAndSemesterOrderByDayOfWeekAscStartSectionAsc(user, sem);
        if (sem == null) repo.deleteByUser(user);
        else repo.deleteByUserAndSemester(user, sem);
        return rows.size();
    }

    // ===================== Prompt 构建 =====================

    private static String parseSystemPrompt() {
        return "你是教务系统课程表解析器。用户会给你某高校教务系统课表页面的 HTML 源码。"
                + "请从中抽取出所有课程，返回 JSON 对象，格式 {\"courses\":[...]}。"
                + "courses 数组每项字段："
                + "courseName(课程名,必填)、teacher(任课教师,可空)、location(上课地点/教室,可空)、"
                + "dayOfWeek(星期几,整数 1-7，周一=1、周日=7)、"
                + "startSection(起始节次,整数,可空)、endSection(结束节次,整数,可空)、"
                + "startTime(上课开始时间,\"HH:mm\"格式,可空)、endTime(\"HH:mm\",可空)、"
                + "weeks(开课周次原文,如\"1-16\"或\"1-8,10-16\",可空)、"
                + "weekParity(\"ALL\"全周|\"ODD\"单周|\"EVEN\"双周，默认 ALL)。"
                + "结构提示：课表常见为【行=大节，列=星期】。上课时间段（如 08:00~09:40）通常写在每一行行首的"
                + "大节标签里（如\"第一大节 (01、02小节) 08:00~09:40\"），而具体课程格子里可能只写了节次（如\"01~02小节\"）；"
                + "请把课程按其节次所属的大节行，补出对应的 startTime/endTime。"
                + "星期请以课程文字中的\"星期X\"为准（星期一=1…星期日/星期天=7），不要只靠列的位置。"
                + "同一门课可能在悬浮提示(tooltip)里重复出现，请【不要重复输出】同一门课（同课名+同星期+同节次只输出一条）。"
                + "规则：同一格子里多门课拆成多条；一门课在不同天/不同节次出现属于不同条目，要各输出一条；"
                + "底部\"备注\"里没有明确星期/节次的课程可忽略；无法确定的字段省略或置 null；绝对不要编造课表里不存在的课程。";
    }

    // ===================== 校验谓词 =====================

    /** 【{"courses":[...]} 至少有一项带非空 courseName 才算可用】 */
    private static boolean hasUsableCourses(JsonNode json) {
        if (json == null) return false;
        var arr = json.path("courses");
        if (!arr.isArray() || arr.isEmpty()) return false;
        for (var item : arr) if (!item.path("courseName").asText("").isBlank()) return true;
        return false;
    }

    // ===================== 辅助方法 =====================

    /** 【清洗 HTML：去脚本/样式/注释，压缩空白，截断到上限。保留标签结构便于 LLM 理解表格】 */
    static String sanitizeHtml(String html) {
        var s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?s)<!--.*?-->", " ");
        s = s.replaceAll("[\\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" {2,}", " ");
        s = s.replaceAll("(?m)^\\s+", "");
        s = s.replaceAll("\\n{3,}", "\n\n");
        s = s.trim();
        if (s.length() > MAX_HTML_CHARS) s = s.substring(0, MAX_HTML_CHARS);
        return s;
    }

    private List<CourseView> toViews(List<CourseEntry> rows) {
        var out = new ArrayList<CourseView>(rows.size());
        for (var c : rows) out.add(CourseView.of(c));
        return out;
    }

    private static int clampDay(int d) {
        return (d >= 1 && d <= 7) ? d : 0;
    }

    private static Integer optInt(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isInt() || node.isLong()) return node.asInt();
        var t = node.asText("").trim();
        if (t.isEmpty()) return null;
        try { return Integer.parseInt(t.replaceAll("[^0-9-]", "")); } catch (Exception e) { return null; }
    }

    /** 【把各种时间写法归一为 HH:mm，识别不出就返回 null】 */
    private static String normalizeTime(String raw) {
        var t = blankToNull(raw);
        if (t == null) return null;
        var m = java.util.regex.Pattern.compile("(\\d{1,2})[:：](\\d{2})").matcher(t);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            return String.format(Locale.ROOT, "%02d:%s", h, m.group(2));
        }
        return null;
    }

    private static WeekParity parseParity(String raw) {
        var t = blankToNull(raw);
        if (t == null) return WeekParity.ALL;
        try { return WeekParity.valueOf(t.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return WeekParity.ALL; }
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        var t = s.trim();
        return t.isEmpty() || "null".equalsIgnoreCase(t) ? null : t;
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
