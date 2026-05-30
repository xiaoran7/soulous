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

> 另一类启动失败是「漏写迁移」：prod `ddl-auto=validate` 下新增实体字段没写 `V{n}__*.sql` → Hibernate 校验失败，见 `DEPLOY.md` / `docs/deployment.md`。

## 实体

- `UserAccount`：账号、昵称、邮箱、角色、token。
- `StudyTask`：任务标题、描述、类型、难度、课程、经验、状态、时间戳。
- `TaskSubmission`：文字凭证、截图 URL、代码片段、链接、学习时长、提交状态。截图文件保存在本地上传目录，表内只记录 URL。
- `AiReview`：审核结果、总分、相关性、完整度、质量分、原因、建议、推荐经验。
- `Appeal`：申诉理由、处理状态、管理员意见。
- `Pet`：等级、当前经验、升级所需经验、心情、饱腹度、成长阶段、状态。
- `ExpLog`：经验变更记录。
- `StudyRecord`：学习时长和摘要记录。

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
