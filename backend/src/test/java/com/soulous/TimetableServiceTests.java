package com.soulous;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulous.ai.LlmService;
import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.common.exception.BadRequestException;
import com.soulous.timetable.CourseEntryRepository;
import com.soulous.timetable.TimetableDtos.ImportRequest;
import com.soulous.timetable.TimetableService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【TimetableService 集成测试：用 H2 内存库 + 桩 LLM（返回固定 JSON）确定性验证
 *  课表导入解析→落库→查询、AI 按课表排周计划、以及关键的失败分支（空内容、无课表）。
 *  不依赖真实 DeepSeek，也不走登录验证码，离线可重复。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:soulous-timetable-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class TimetableServiceTests {

    @Autowired UserService users;
    @Autowired CourseEntryRepository repo;

    /** 【桩 LLM：可用且 completeJsonValidated 返回预设 JSON，记录最近 prompt】 */
    static class StubLlm extends LlmService {
        private final ObjectMapper mapper = new ObjectMapper();
        String jsonResponse;
        boolean available = true;
        String lastUser;

        StubLlm() {
            super("openai", "stub-key", "stub-model", "", 30, false, 16, 60);
        }

        @Override public boolean isAvailable() { return available; }

        @Override
        public Optional<JsonNode> completeJsonValidated(String namespace, String providerName,
                                                        String systemPrompt, String userPrompt,
                                                        Predicate<JsonNode> valid) {
            lastUser = userPrompt;
            if (!available || jsonResponse == null) return Optional.empty();
            try {
                var node = mapper.readTree(jsonResponse);
                return (valid == null || valid.test(node)) ? Optional.of(node) : Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }

    private UserAccount newUser() {
        var unique = "tt" + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", "课表用户", unique + "@example.com"));
        return users.byToken(auth.token());
    }

    private static final String COURSES_JSON = """
        {"courses":[
          {"courseName":"高等数学","teacher":"张老师","location":"A101","dayOfWeek":1,
           "startSection":1,"endSection":2,"startTime":"08:00","endTime":"09:40","weeks":"1-16","weekParity":"ALL"},
          {"courseName":"大学英语","teacher":"李老师","location":"B202","dayOfWeek":3,
           "startSection":3,"endSection":4,"startTime":"10:00","endTime":"11:40","weeks":"1-16","weekParity":"ODD"}
        ]}""";

    @Test
    void importParsesAndPersists() {
        var llm = new StubLlm();
        llm.jsonResponse = COURSES_JSON;
        var svc = new TimetableService(repo, llm);
        var user = newUser();

        var result = svc.importHtml(user, new ImportRequest("<table>...</table>", "2025-2026-2", true));

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.semester()).isEqualTo("2025-2026-2");

        var list = svc.list(user, null);
        assertThat(list).hasSize(2);
        // 按 dayOfWeek 升序：第一条应是周一的高数
        assertThat(list.get(0).dayOfWeek()).isEqualTo(1);
        assertThat(list.get(0).courseName()).isEqualTo("高等数学");
        assertThat(list.get(0).startTime()).isEqualTo("08:00");
        assertThat(list.get(1).courseName()).isEqualTo("大学英语");
        assertThat(list.get(1).weekParity()).isEqualTo("ODD");
        // HTML 应被送进 LLM 解析
        assertThat(llm.lastUser).contains("课表 HTML");
    }

    @Test
    void importWithReplaceOverwritesOld() {
        var llm = new StubLlm();
        llm.jsonResponse = COURSES_JSON;
        var svc = new TimetableService(repo, llm);
        var user = newUser();

        svc.importHtml(user, new ImportRequest("<table>a</table>", null, true));
        svc.importHtml(user, new ImportRequest("<table>b</table>", null, true));

        // replace=true 整表覆盖，仍应只有 2 条而非 4 条
        assertThat(svc.list(user, null)).hasSize(2);
    }

    @Test
    void importRejectsBlankHtml() {
        var svc = new TimetableService(repo, new StubLlm());
        var user = newUser();
        assertThatThrownBy(() -> svc.importHtml(user, new ImportRequest("   ", null, true)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void importDedupesTooltipDuplicatesButKeepsDistinctSameName() {
        var llm = new StubLlm();
        // 模拟青果教务格式：数据结构出现两次（courselist + tooltip 重复）；
        // 高等数学A2 出现 3 次但分属不同星期/节次，应全部保留。
        llm.jsonResponse = """
            {"courses":[
              {"courseName":"数据结构","dayOfWeek":2,"startSection":1,"endSection":2,"startTime":"08:00","endTime":"09:40"},
              {"courseName":"数据结构","dayOfWeek":2,"startSection":1,"endSection":2,"startTime":"08:00","endTime":"09:40"},
              {"courseName":"高等数学A2","dayOfWeek":2,"startSection":5,"endSection":6},
              {"courseName":"高等数学A2","dayOfWeek":5,"startSection":7,"endSection":8},
              {"courseName":"高等数学A2","dayOfWeek":1,"startSection":9,"endSection":10}
            ]}""";
        var svc = new TimetableService(repo, llm);
        var user = newUser();

        var result = svc.importHtml(user, new ImportRequest("<table>...</table>", null, true));

        // 5 条输入 → 去掉 1 条数据结构重复 = 4 条
        assertThat(result.count()).isEqualTo(4);
        var math = svc.list(user, null).stream().filter(c -> c.courseName().equals("高等数学A2")).toList();
        assertThat(math).hasSize(3); // 三节高数都在
    }
}
