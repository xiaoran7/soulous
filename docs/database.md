# 数据库说明

## 默认数据库

默认使用 H2 文件数据库：

```text
jdbc:h2:file:./data/soulous;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE
```

H2 Console:

```text
http://localhost:8080/h2-console
```

## MySQL profile

运行：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

默认配置：

```text
jdbc:mysql://localhost:3306/soulous
username: root
password:
```

## Flyway 迁移

Schema 由 Flyway 管理，h2 / mysql 各一套脚本，按方言自动选目录：

```text
backend/src/main/resources/db/migration/{h2,mysql}/V{n}__*.sql
```

**铁律：已经跑过的迁移文件不要再改内容。** 要变更就新加一个 `V{n+1}__*.sql`（h2 + mysql 同名两份都写）。

改了已应用的迁移，下次启动会报 checksum 校验失败、拒绝启动：

```text
FlywayValidateException: Migrations have failed validation
Migration checksum mismatch for migration version N
```

修法（本地 H2，pom 里没装 flyway-maven-plugin，直接调插件 goal 即可，不丢数据）：

```bash
cd backend
mvn org.flywaydb:flyway-maven-plugin:repair \
  -Dflyway.url="jdbc:h2:file:./data/soulous;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE" \
  -Dflyway.user=sa -Dflyway.password= \
  -Dflyway.locations=filesystem:src/main/resources/db/migration/h2
```

repair 会把历史表 `flyway_schema_history` 里的 checksum 刷成当前文件的值。实在不在乎本地数据，也可直接删 `backend/data/soulous*.db` 让迁移全新重跑。
prod（MySQL）同样会中招——换成 mysql 的 url / 凭据和 `db/migration/mysql` 目录跑 repair，**别直接删生产库**。

> 另一类启动失败是「漏写迁移」：prod `ddl-auto=validate` 下新增实体字段没写 `V{n}__*.sql` → Hibernate 校验失败，补列方法见 `DEPLOY.md` §9。

## 实体

- `UserAccount`：账号、昵称、邮箱、角色、token、金币余额（`coin_balance`，h2 V12 / mysql V13）、伴侣昵称（`companion_nickname`，全局跨宠物共享，经 `PATCH /api/pet` 设置，h2 V17 / mysql V18）、AI 记忆开关（`ai_memory_enabled`，默认 true，h2 V18 / mysql V19）。
- `StudyTask`：任务标题、描述、类型、难度、课程、经验、状态、时间戳。
- `TaskSubmission`：文字凭证、截图 URL、代码片段、链接、学习时长、提交状态。截图文件保存在本地上传目录，表内只记录 URL。
- `AiReview`：审核结果、总分、相关性、完整度、质量分、原因、建议、推荐经验。
- `Appeal`：申诉理由、处理状态、管理员意见。
- `Pet`（表 `owned_pet`，h2 V14 / mysql V15）：**用户拥有的一只宠物**——等级、当前经验、升级所需经验、心情、饱腹度、成长阶段、状态、品种、`active`(出战)、获得时间。一个用户可拥有多只（多对一），各自独立成长，`active=true` 为出战宠物。**取代了旧的一对一 `pet` 表**（旧表迁移时保留不删，数据已复制进 `owned_pet`）。
- `PetSpecies`（表 `pet_species`，h2 V14 / mysql V15）：宠物市场目录——slug、名称、稀有度、价格、是否入门款(`starter`)、sprite 路径、排序。seed 4 款（暂复用同一 spritesheet）。
- `CoinLedger`（表 `coin_ledger`，h2 V12 / mysql V13）：金币流水（正入账/负出账 + 余额快照 + 来源）。余额本身落在 `user_account.coin_balance`。
- `CheckinEntry`（表 `daily_checkin`，h2 V13 / mysql V14）：每日打卡——(user, date) 唯一，记连续天数 streak 与发放的经验/金币快照。
- `StudyRoom` / `RoomMember`（表 `study_room` / `room_member`，h2 V15 / mysql V16）：共享自习室与成员在线/专注心跳状态（`last_seen_at` 在 90s 窗口内判在线）。
- `ExpLog`：经验变更记录。
- `StudyRecord`：学习时长和摘要记录。
- `CourseEntry`（表 `course_entry`，V7）：课表一节课——课程名、教师、地点、星期(1-7)、起止节次、起止时间、周次原文、单双周(ALL/ODD/EVEN)、学期。来源是教务系统爬虫同步（`hut_schedule.py`）或手动新增。
- `ExamEntry`（表 `exam_entry`，h2 V11 / mysql V12）：考试安排一场考试——课程名、课程编号、教师、考试时间、考场、校区、座位号、场次、准考证号、备注、学期。随课表同步（`--mode all`）一并抓回，按 (user, semester) 分学期；考试接口不带学期，由同步的学期标识打上。
- `GradeEntry`（表 `grade_entry`，h2 V11 / mysql V12）：课程成绩一门——开课学期、课程名/编号、开课单位、成绩、成绩标识、学分、绩点、总学时、考核方式、考试性质、课程属性/性质。成绩查询天然跨学期，每条自带开课学期；同步时整体覆盖（按 user 全删再写）。学分/绩点保留教务原始字符串（可能为「优秀」等非数值），由前端按需解析算 GPA/学分小结。
- `ChatCategory` / `ChatConversation` / `ChatMessage`（表 `chat_category` / `chat_conversation` / `chat_message`，mysql V10 / h2 V9）：AI 拆解的「分类 → 对话 → 消息」三级结构。对话可选归入某分类（`category_id` 为空 = 默认组），`pending_plan_json` 非空表示有待确认计划草案，`running_summary` 滚动摘要控长；`chat_message.content` 用 `MEDIUMTEXT`（附件文本可达 3 万字符）。
- `Goal` / `PlanningSession` / `SessionTurn`（旧目标拆解，**已下线**）：2026-06-01 的对话重构后这些表与服务不再有对外接口，但暂时保留（RAG 目标记忆、每日复盘、`study_task.goal_id` 仍引用）。新功能不要再往这些表写。

## 主要状态

任务状态：

```text
TODO, DOING, SUBMITTED, AI_APPROVED, AI_REJECTED, NEED_MORE,
APPEALING, MANUAL_APPROVED, MANUAL_REJECTED, COMPLETED
```

AI 审核结果：

```text
PASS, REJECT, NEED_MORE, MANUAL
```
