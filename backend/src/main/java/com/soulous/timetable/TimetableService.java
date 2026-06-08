package com.soulous.timetable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulous.ai.LlmService;
import com.soulous.auth.UserAccount;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.timetable.TimetableDtos.CourseView;
import com.soulous.timetable.TimetableDtos.SyncRequest;
import com.soulous.timetable.TimetableDtos.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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

    /**
     * 【全局并发闸：同时最多允许 N 个教务爬虫子进程在跑。
     *  每个爬虫会拉起一个 Chromium（Playwright），单进程数百 MB，
     *  仅靠 per-user 限流挡不住"多用户同时点同步"打爆内存，这里做全局上限。】
     */
    private static final int MAX_CONCURRENT_CRAWLERS = 3;
    private static final Semaphore CRAWLER_SLOTS = new Semaphore(MAX_CONCURRENT_CRAWLERS);
    /** 【等待并发槽位的最长时间，拿不到就快速失败返回"繁忙"，不让请求线程长时间堆积】 */
    private static final long SLOT_WAIT_SECONDS = 5;
    /** 【单个爬虫子进程的总超时，超时强杀整棵进程树】 */
    private static final long CRAWLER_TIMEOUT_SECONDS = 120;

    private final CourseEntryRepository repo;
    private final LlmService llm;

    /** 【脚本只在 JVM 内解压一次到固定路径，之后所有请求复用，
     *  避免并发请求各自 copy 同一文件造成读到半截脚本的竞争（见 getScriptPath）。】 */
    private volatile Path cachedScriptPath;

    public TimetableService(CourseEntryRepository repo, LlmService llm) {
        this.repo = repo;
        this.llm = llm;
    }

    // ===================== 同步 (爬虫) =====================

    /**
     * 【把 classpath 里的爬虫脚本解压到固定临时路径并返回。
     *  整个 JVM 生命周期只解压一次（首个请求触发，后续直接复用缓存路径），
     *  从而消除"多个并发请求同时 REPLACE_EXISTING 覆写同一文件、而别的线程的
     *  python 正在读 → 读到半截脚本"的竞争。脚本内容是静态的，没必要每次请求都 copy。】
     */
    protected Path getScriptPath() throws IOException {
        Path cached = cachedScriptPath;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedScriptPath != null) {
                return cachedScriptPath;
            }
            String scriptName = "hut_schedule.py";
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "soulous");
            Files.createDirectories(tempDir);
            Path scriptPath = tempDir.resolve(scriptName);

            // 从 classpath 解压脚本到固定路径，仅在此处、且仅一次（持锁）写盘。
            try (InputStream is = resolveScriptStream(scriptName)) {
                if (is == null) {
                    throw new FileNotFoundException("Cannot find " + scriptName + " in classpath resources.");
                }
                Files.copy(is, scriptPath, StandardCopyOption.REPLACE_EXISTING);
            }
            cachedScriptPath = scriptPath;
            return scriptPath;
        }
    }

    /** 【优先 scripts/ 子目录，回退到 classpath 根，找不到返回 null】 */
    private InputStream resolveScriptStream(String scriptName) {
        InputStream is = getClass().getClassLoader().getResourceAsStream("scripts/" + scriptName);
        if (is == null) {
            is = getClass().getClassLoader().getResourceAsStream(scriptName);
        }
        return is;
    }

    /** 【可配置的 Python 解释器：优先环境变量 SOULOUS_PYTHON（VPS 上常是 python3），默认 python】 */
    private static String pythonExecutable() {
        String exe = System.getenv("SOULOUS_PYTHON");
        return (exe == null || exe.isBlank()) ? "python" : exe;
    }

    /** 【杀掉子进程及其全部后代（Playwright 会拉起 Chromium 等子进程），
     *  避免超时强杀时只杀了 python 根进程、残留一堆孤儿浏览器进程。】 */
    private static void killProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    //根据爬虫输出的日志（output）提取更友好的错误信息
    private String parseErrorMsg(String output) {
        if (output == null || output.isBlank()) {
            return "未知异常";
        }
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("[!]")) {
                return line.substring(3).trim();
            }
            if (line.contains("Error:")) {
                return line.substring(line.indexOf("Error:")).trim();
            }
        }
        return "请检查账号密码或学校教务系统状态";
    }

    /**
     * 【通过输入账号密码调用 Playwright/Requests 爬虫同步课表】
     */

    //把学生在学校教务系统里的课表，一键同步到你的系统数据库中
    @Transactional
    public SyncResult syncFromCrawler(UserAccount user, SyncRequest req) {

        //参数校验
        if (req == null || req.username() == null || req.username().isBlank() ||
                req.password() == null || req.password().isBlank()) {
            throw new BadRequestException("账号或密码不能为空。");
        }

        //为脚本返回的数据准备一个json文件
        Path scriptPath;
        Path tempJsonOutput;
        try {
            scriptPath = getScriptPath();
            tempJsonOutput = Files.createTempFile("timetable_sync_", ".json");
        } catch (IOException e) {
            log.error("Failed to prepare python script or temp file", e);
            throw new BadRequestException("系统内部错误：无法初始化课表爬虫环境。");
        }

        //把爬取的json数据读入内存，解析成课程列表，写入数据库
        boolean slotAcquired = false;
        try {
            // 全局并发闸：拿不到槽位（已有 N 个爬虫在跑）就快速返回繁忙，避免 Chromium 进程无限堆积。
            if (!CRAWLER_SLOTS.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new BadRequestException("课表同步繁忙，请稍后重试。");
            }
            slotAcquired = true;

            List<String> command = List.of(
                pythonExecutable(),
                scriptPath.toString(),
                "--user", req.username(),
                "--pwd-stdin",          // 密码改走 stdin，命令行/进程列表/日志均不含明文
                "--relogin",            // 服务端强制每次重新登录，杜绝共享 cookie 缓存导致的跨用户串号
                "--term", "auto",
                "--mode", "schedule",
                "--out", tempJsonOutput.toString()
            );

            // command 已不含密码，可安全记录；密码下面单独经 stdin 传入。
            log.info("Starting schedule crawler for user={}", user.id);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            // 服务端模式开关：跳过运行时自动装依赖、强制无头、禁用 scratch 调试落盘、不持久化 cookie。
            pb.environment().put("SOULOUS_SERVER_MODE", "1");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 密码一次性写入 stdin 后立即关闭，进程列表/日志/环境变量都看不到。
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write((req.password() + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException ignore) {
                // 子进程可能已提前退出（如启动失败），交由下面的退出码逻辑处理。
            }

            // 在独立线程里 drain stdout：既防止管道缓冲填满导致死锁，
            // 也保证下面的 waitFor 超时真正生效（否则会一直阻塞在 readLine 上）。
            StringBuilder processOutput = new StringBuilder();
            Thread drainThread = new Thread(() -> {
                try (var reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (processOutput) {
                            processOutput.append(line).append("\n");
                        }
                    }
                } catch (IOException ignore) {
                    // 进程被强杀时读流抛错属正常，忽略。
                }
            }, "timetable-crawler-drain-" + user.id);
            drainThread.setDaemon(true);
            drainThread.start();

            boolean finished = process.waitFor(CRAWLER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                killProcessTree(process);
                throw new BadRequestException("教务系统同步超时，请稍后重试。");
            }
            // 进程已退出，等 drain 线程收尾以拿到完整输出。
            drainThread.join(TimeUnit.SECONDS.toMillis(5));

            int exitCode = process.exitValue();
            String outputStr;
            synchronized (processOutput) {
                outputStr = processOutput.toString();
            }
            if (exitCode != 0) {
                log.warn("Crawler process exited with error code {}. Output:\n{}", exitCode, outputStr);
                if (outputStr.contains("密码错误") || outputStr.contains("RuntimeError: 密码错误")) {
                    throw new BadRequestException("同步失败：教务系统登录密码错误。");
                }
                if (outputStr.contains("TimeoutError")) {
                    throw new BadRequestException("同步失败：登录教务系统超时。这通常是学校教务系统繁忙或需要滑块验证导致，请稍后重试。");
                }
                throw new BadRequestException("同步失败：教务系统访问异常。错误详情：" + parseErrorMsg(outputStr));
            }

            //准备写进内存
            byte[] jsonData = Files.readAllBytes(tempJsonOutput);
            if (jsonData.length == 0) {
                throw new BadRequestException("同步失败：未能获取到有效的课表数据。");
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonData);

            String semester = root.path("学期").asText(null);
            if (semester == null || semester.isBlank()) {
                throw new BadRequestException("同步失败：未能获取到课表学期。");
            }

            // 清空旧课表
            repo.deleteByUserAndSemester(user, semester);

            JsonNode coursesNode = root.path("课程");
            List<CourseEntry> parsed = new ArrayList<>();
            var seen = new java.util.HashSet<String>();
            for (JsonNode item : coursesNode) {
                String name = item.path("课程名").asText("").trim();
                if (name.isBlank()) continue;

                int day = item.path("星期序号").asInt(0);
                if (day < 1 || day > 7) continue;

                // 节次解析，例如 "1-2"
                String jc = item.path("节次").asText("").trim();
                var dedupKey = name + "|" + day + "|" + jc;
                if (!seen.add(dedupKey)) continue;

                CourseEntry c = new CourseEntry();
                c.user = user;
                c.courseName = trunc(name, 200);
                c.teacher = trunc(blankToNull(item.path("教师").asText(null)), 100);
                c.location = trunc(blankToNull(item.path("地点").asText(null)), 120);
                c.dayOfWeek = day;

                if (!jc.isEmpty()) {
                    String[] parts = jc.split("[-~]");
                    if (parts.length > 0) {
                        try {
                            c.startSection = Integer.parseInt(parts[0].trim());
                            c.endSection = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : c.startSection;
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    }
                }

                // 时间解析，例如 "08:00~09:40"
                String timeRange = item.path("上课时间").asText("").trim();
                if (!timeRange.isEmpty()) {
                    String[] times = timeRange.split("[~-]");
                    if (times.length > 0) {
                        c.startTime = trunc(normalizeTime(times[0].trim()), 8);
                        c.endTime = times.length > 1 ? trunc(normalizeTime(times[1].trim()), 8) : null;
                    }
                }

                c.weeks = trunc(blankToNull(item.path("周次").asText(null)), 60);

                // 单双周简单判断
                String weeksRaw = item.path("周次").asText("");
                if (weeksRaw.contains("单")) {
                    c.weekParity = WeekParity.ODD;
                } else if (weeksRaw.contains("双")) {
                    c.weekParity = WeekParity.EVEN;
                } else {
                    c.weekParity = WeekParity.ALL;
                }

                c.semester = semester;
                parsed.add(c);
            }

            if (parsed.isEmpty()) {
                throw new BadRequestException("未能在该学期中识别到任何有效课程，请检查您的教务课表。");
            }

            List<CourseEntry> saved = repo.saveAll(parsed);
            log.info("Timetable synced via crawler: user={} semester={} courses={}", user.id, semester, saved.size());

            String weekStart = root.path("学期信息").path("开学日期").asText("");

            return new SyncResult(saved.size(), semester, weekStart, toViews(saved));

        } catch (IOException | InterruptedException e) {
            log.error("Failed to execute schedule crawler", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BadRequestException("系统同步出错，请稍后再试。错误：" + e.getMessage());
        } finally {
            if (slotAcquired) {
                CRAWLER_SLOTS.release();
            }
            try {
                Files.deleteIfExists(tempJsonOutput);
            } catch (IOException e) {
                // ignore
            }
        }
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
