package com.soulous;

import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.common.exception.BadRequestException;
import com.soulous.timetable.CourseEntryRepository;
import com.soulous.timetable.ExamEntryRepository;
import com.soulous.timetable.GradeEntryRepository;
import com.soulous.timetable.TimetableDtos.SyncRequest;
import com.soulous.timetable.TimetableService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【TimetableService 集成测试：用 H2 内存库 + 模拟 Python 爬虫脚本进行验证】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:soulous-timetable-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class TimetableServiceTests {

    @Autowired UserService users;
    @Autowired CourseEntryRepository repo;
    @Autowired ExamEntryRepository examRepo;
    @Autowired GradeEntryRepository gradeRepo;

    private Path mockScript;

    private static final String SEMESTER_INFO = """
        "学期信息": {
            "当前周次": 1,
            "起始周": 1,
            "结束周": 18,
            "总周数": 18,
            "开学日期": "2026-03-02"
        }
        """;

    private static final String COURSES_JSON = """
        {
          "学期": "2025-2026-2",
          "课程": [
            {"课程名": "高等数学", "教师": "张老师", "地点": "A101", "星期序号": 1, "节次": "1-2", "上课时间": "08:00~09:40", "周次": "1-16"},
            {"课程名": "大学英语", "教师": "李老师", "地点": "B202", "星期序号": 3, "节次": "3-4", "上课时间": "10:00~11:40", "周次": "1-16周(单)"}
          ],
          %s
        }
        """.formatted(SEMESTER_INFO);

    private static final String COURSES_JSON_DUP = """
        {
          "学期": "2025-2026-2",
          "课程": [
            {"课程名": "数据结构", "星期序号": 2, "节次": "1-2", "上课时间": "08:00~09:40", "周次": "1-16"},
            {"课程名": "数据结构", "星期序号": 2, "节次": "1-2", "上课时间": "08:00~09:40", "周次": "1-16"},
            {"课程名": "高等数学A2", "星期序号": 2, "节次": "5-6", "上课时间": "14:00~15:40", "周次": "1-16"},
            {"课程名": "高等数学A2", "星期序号": 5, "节次": "7-8", "上课时间": "16:00~17:40", "周次": "1-16"},
            {"课程名": "高等数学A2", "星期序号": 1, "节次": "9-10", "上课时间": "19:00~20:40", "周次": "1-16"}
          ],
          %s
        }
        """.formatted(SEMESTER_INFO);

    // 课表 + 考试安排 + 成绩（跨学期）一体的 --mode all 输出样例
    private static final String ALL_JSON = """
        {
          "学期": "2025-2026-2",
          "课程": [
            {"课程名": "高等数学", "教师": "张老师", "地点": "A101", "星期序号": 1, "节次": "1-2", "上课时间": "08:00~09:40", "周次": "1-16"}
          ],
          "考试安排": [
            {"课程名称": "高等数学", "课程编号": "MATH001", "授课教师": "张老师", "考试时间": "2026-06-20 09:00~11:00", "考场": "A101", "考试校区": "云塘校区", "座位号": "12", "考试场次": "第1场", "准考证号": "Z123", "备注": ""},
            {"课程名称": "大学英语", "课程编号": "ENG001", "授课教师": "李老师", "考试时间": "2026-06-18 14:00~16:00", "考场": "B202", "考试校区": "云塘校区", "座位号": "5", "考试场次": "第2场", "准考证号": "Z124", "备注": "闭卷"}
          ],
          "成绩": [
            {"开课学期": "2025-2026-1", "课程编号": "PE001", "课程名称": "体育", "开课单位": "体育学院", "成绩": "良好", "学分": "1", "绩点": "3.5", "考试性质": "初修", "课程属性": "必修"},
            {"开课学期": "2025-2026-2", "课程编号": "MATH001", "课程名称": "高等数学", "开课单位": "理学院", "成绩": "88", "学分": "4", "绩点": "3.8", "考试性质": "初修", "课程属性": "必修"}
          ],
          %s
        }
        """.formatted(SEMESTER_INFO);

    static class TestTimetableService extends TimetableService {
        private final Path scriptPath;

        public TestTimetableService(CourseEntryRepository repo, ExamEntryRepository examRepo,
                                    GradeEntryRepository gradeRepo, Path scriptPath) {
            super(repo, examRepo, gradeRepo, null);
            this.scriptPath = scriptPath;
        }

        @Override
        protected Path getScriptPath() throws IOException {
            return scriptPath;
        }
    }

    private Path createMockPythonScript(String jsonContent) throws IOException {
        Path script = Files.createTempFile("mock_crawler_", ".py");
        String content = """
            import sys
            import json
            
            # Find --out parameter
            out_file = None
            for i in range(len(sys.argv)):
                if sys.argv[i] == '--out' and i + 1 < len(sys.argv):
                    out_file = sys.argv[i+1]
                    break
            
            if out_file:
                with open(out_file, 'w', encoding='utf-8') as f:
                    f.write('''%s''')
                sys.exit(0)
            else:
                sys.exit(1)
            """.formatted(jsonContent);
        Files.writeString(script, content);
        return script;
    }

    private Path createErrorPythonScript() throws IOException {
        Path script = Files.createTempFile("mock_crawler_err_", ".py");
        String content = """
            import sys
            print("RuntimeError: 密码错误")
            sys.exit(1)
            """;
        Files.writeString(script, content);
        return script;
    }

    private UserAccount newUser() {
        var unique = "tt" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", "课表用户", unique + "@example.com"));
        return users.byToken(auth.token());
    }

    @AfterEach
    void cleanup() throws IOException {
        if (mockScript != null) {
            Files.deleteIfExists(mockScript);
        }
    }

    @Test
    void syncParsesAndPersists() throws IOException {
        mockScript = createMockPythonScript(COURSES_JSON);
        var svc = new TestTimetableService(repo, examRepo, gradeRepo, mockScript);
        var user = newUser();

        var result = svc.syncFromCrawler(user, new SyncRequest("test_user", "test_pass"));

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.semester()).isEqualTo("2025-2026-2");
        assertThat(result.weekStart()).isEqualTo("2026-03-02");

        var list = svc.list(user, null);
        assertThat(list).hasSize(2);
        // 按 dayOfWeek 升序：第一条应是周一的高数
        assertThat(list.get(0).dayOfWeek()).isEqualTo(1);
        assertThat(list.get(0).courseName()).isEqualTo("高等数学");
        assertThat(list.get(0).startTime()).isEqualTo("08:00");
        assertThat(list.get(1).courseName()).isEqualTo("大学英语");
        assertThat(list.get(1).weekParity()).isEqualTo("ODD");
    }

    @Test
    void syncAlsoPersistsExamsAndGrades() throws IOException {
        mockScript = createMockPythonScript(ALL_JSON);
        var svc = new TestTimetableService(repo, examRepo, gradeRepo, mockScript);
        var user = newUser();

        var result = svc.syncFromCrawler(user, new SyncRequest("test_user", "test_pass"));

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.examCount()).isEqualTo(2);
        assertThat(result.gradeCount()).isEqualTo(2);

        // 考试安排：按学期过滤 + 时间升序（6/18 在 6/20 前）
        var exams = svc.listExams(user, "2025-2026-2");
        assertThat(exams).hasSize(2);
        assertThat(exams.get(0).courseName()).isEqualTo("大学英语");
        assertThat(exams.get(0).campus()).isEqualTo("云塘校区");
        assertThat(exams.get(1).courseName()).isEqualTo("高等数学");

        // 成绩：跨学期返回全部；按学期过滤只取该学期
        assertThat(svc.listGrades(user, null)).hasSize(2);
        var sem1 = svc.listGrades(user, "2025-2026-1");
        assertThat(sem1).hasSize(1);
        assertThat(sem1.get(0).courseName()).isEqualTo("体育");
        assertThat(sem1.get(0).gpa()).isEqualTo("3.5");
    }

    @Test
    void syncHandlesPasswordError() throws IOException {
        mockScript = createErrorPythonScript();
        var svc = new TestTimetableService(repo, examRepo, gradeRepo, mockScript);
        var user = newUser();

        assertThatThrownBy(() -> svc.syncFromCrawler(user, new SyncRequest("test_user", "wrong_pass")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("登录密码错误");
    }

    @Test
    void syncDedupesTooltipDuplicatesButKeepsDistinctSameName() throws IOException {
        mockScript = createMockPythonScript(COURSES_JSON_DUP);
        var svc = new TestTimetableService(repo, examRepo, gradeRepo, mockScript);
        var user = newUser();

        var result = svc.syncFromCrawler(user, new SyncRequest("test_user", "test_pass"));

        // 5 courses in mock JSON, but one (数据结构) is duplicate in day/section, so it should keep 4 entries
        assertThat(result.count()).isEqualTo(4);
        var math = svc.list(user, null).stream().filter(c -> c.courseName().equals("高等数学A2")).toList();
        assertThat(math).hasSize(3); // 三节高数都在
    }
}
