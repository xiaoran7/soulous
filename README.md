# Soulous

> AI 驱动的全栈游戏化学习闭环：长期目标 → AI 智能拆解 → 专注计时 → 凭证提交 → 多模型 AI 审核 → RAG 长时记忆 → 经验奖励 → 虚拟宠物成长。

把碎片化的学习过程变成可量化、可追溯、能正反馈的闭环。

---

## 技术栈

**后端** Spring Boot 3.4 · Java 21 · Maven · Spring Data JPA · Spring Security · JWT (jjwt 0.12) · Flyway · Bucket4j · Micrometer / Prometheus · H2（默认）/ MySQL · Thumbnailator

**前端** React 19 · Vite 6 · TypeScript 5 · Vitest · Recharts（懒加载）· lucide-react

**AI** 可插拔 `LlmService` 策略：mock（默认）/ DeepSeek / OpenAI 兼容 / Anthropic，统一接 LRU+TTL 缓存与失败遥测

---

## 核心能力

- **账户与安全**：注册登录、SVG 图形验证码、密码强度策略、JWT 双 token（1h access + 30d refresh，HttpOnly cookie、SHA-256 入库、自动轮换、复用触发全设备登出）、`audit_log` 全量审计
- **目标与任务**：长期目标 CRUD（软删 `ARCHIVED`）、AI 拆解会话（Planning Session 对话流、`COMMITTED` 历史保留、闲置 session 定时清理）、任务生命周期管理
- **专注 / 凭证**：番茄钟专注时长记录、图文 / 代码 / 截图凭证提交（白名单 + 5MB 限制 + 自动压缩 1920px / JPEG 85%）、鉴权下载 `/uploads/**`
- **AI 中枢**：
  - 智能拆解（交互式 Planning Session）
  - 凭证审核引擎（相关性 / 完整度 / 质量分多维评估，自动发经验）
  - 每日复盘（基于当日行为生成动态日报）
  - RAG 长时记忆库 + 时间衰减检索（`score = cosineSim · 0.5^(ageDays / halfLife)`，默认半衰期 90 天）；索引来源覆盖 `GOAL_MEMORY` / `SESSION_SUMMARY` / `COMPLETED_TASK` / `DAILY_REVIEW`，并在凭证审核（`AiService.review`）、任务追问（`generateQuestion`）、目标拆解（`decompose`）、每日复盘（`DailyReviewService`）四路 LLM 调用前注入 `[用户历史相关记忆]` 段，让 AI 真正"认识"用户
  - 上下文感知内容风控（input/output 双向 PASS/FLAG/BLOCK，命中入 `moderation_log`）
- **宠物 / 数据**：经验升级、心情随活跃度变化、自定义头像、宠物 sprite 资源（[`frontend/public/pets/`](frontend/public/pets/)）；今日指标看板、近 7 天热力图、领域占比、趋势图
- **管理后台**：全站提交查询、人工强制覆盖 AI 结果（通过 / 打回 / 补充）、申诉处理、管理员建号、角色变更，全部入 `admin_audit_log`
- **通知中心**：AI 审核完成 / 申诉处理等关键事件入 `notification`；前端铃铛优先走 SSE 实时推送（`GET /api/notifications/stream`），断流自动降级到 60s 轮询；可选邮件 sink（`spring-boot-starter-mail`，配置开关 `soulous.notification.email.enabled`）；支持单条 / 批量已读

### 生产可靠性（Phase 1 加固）

- **Flyway 迁移**：H2 / MySQL 各一份基线；现有库 `baseline-on-migrate` 平滑接入；prod 强制 `ddl-auto=validate`
- **应用层限流**：`@RateLimit` 注解 + Bucket4j；登录 / 注册 5/min（IP），AI 调用 60/h ∧ 200/day（用户），命中返回 `429 + Retry-After`
- **存储 GC**：每日 03:00 扫描 24h 前无引用对象，默认 dry-run
- **可观测性**：Spring Actuator + Prometheus；自定义业务指标见下文

---

## 快速启动

> 前置：JDK 21+、Maven 3.9+、Node 18+

```bash
# 后端
cd backend && mvn spring-boot:run

# 前端
cd frontend && npm install && npm run dev
```

打开 http://localhost:5173 。

**首个管理员**：仓库不再播种任何默认账号。通过环境变量 bootstrap：

```bash
SOULOUS_BOOTSTRAP_ADMIN_USERNAME=admin
SOULOUS_BOOTSTRAP_ADMIN_PASSWORD=<strong-password>
SOULOUS_BOOTSTRAP_ADMIN_NICKNAME=Admin
```

之后的账号通过管理员 UI 创建或前端注册。

---

## 验证

```bash
cd backend && mvn test         # 后端单元 / 集成测试 (185 用例)
cd frontend && npm test        # 前端 vitest (15 用例)
cd frontend && npm run build   # 前端构建
cd frontend && npm run test:e2e  # Playwright 端到端 (需后端在跑)
```

### E2E 测试

`frontend/e2e/` 下放 Playwright spec。默认 webServer 会自动起 Vite，但后端需要手动起并加几个 E2E 友好的开关：

```bash
cd backend
SOULOUS_CAPTCHA_ENABLED=false \
SOULOUS_LLM_PROVIDER=mock \
SOULOUS_RATE_LIMIT_ENABLED=false \
mvn spring-boot:run
```

然后 `cd frontend && npm run test:e2e`（或 `npm run test:e2e:ui` 用 Playwright 的可视化 UI 跑）。
当前覆盖：注册新用户 → 跳到任务页 → 创建任务 → 验证任务出现在列表。提交流程的 AI 审核 + SSE 通知验证留给后续，因为依赖异步处理时序，单独跑容易闪烁。

---

## 切换到 MySQL

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

默认连接 `jdbc:mysql://localhost:3306/soulous`（`root` / 空密码），在 [`backend/src/main/resources/application.yml`](backend/src/main/resources/application.yml) 调整。

---

## 数据库迁移（Flyway）

脚本路径：`backend/src/main/resources/db/migration/{h2,mysql}/V<n>__<desc>.sql`

- 现有数据库：首次启动自动 baseline 至 V1，跳过基线、只跑 V2+ 增量
- 新数据库：从 V1 起依次执行
- prod：`spring.jpa.hibernate.ddl-auto=validate`，schema 完全由 Flyway 管理
- dev：保留 `update`，方便本地迭代

实体改动后重新导出基线（仅在不得已时使用，日常用 V<n> 增量）：

```bash
cd backend && mvn test -Dtest=SchemaDumpTool -Dsoulous.schemaDump=true
```

---

## 审计日志

通用安全 / 管理审计落 `audit_log`：

- `LOGIN_SUCCESS` / `LOGIN_FAILED`（带尝试用户名快照；API 响应保持通用，不泄露用户名是否存在）
- `LOGOUT` / `LOGOUT_ALL`（含撤销 refresh token 数量）
- `PASSWORD_CHANGED`
- `REFRESH_TOKEN_REPLAYED`（旧 token 复用 → 级联清空该用户所有会话；审计写入用 `REQUIRES_NEW` 事务，401 回滚也不会吞这条记录）
- `ADMIN_CREATE_USER` / `ADMIN_UPDATE_USER_ROLE`

查询：`GET /api/admin/audit-log?action=&actorUserId=&from=&to=&page=&size=`（仅 ADMIN，标准分页）。

`audit_log` / `admin_audit_log`（审核操作流水）/ `moderation_log`（内容风控）是**三张互相独立**的表，互不替代。审计行**不会自动清理**——增长缓慢，归档 / 截断由运维手动处理。

---

## 可观测性

| 端点 | 权限 | 用途 |
| --- | --- | --- |
| `GET /actuator/health` | 公开 | 探活；细节默认隐藏（`SOULOUS_HEALTH_DETAILS=always` 展开） |
| `GET /actuator/info` | 公开 | 应用信息 |
| `GET /actuator/prometheus` | ADMIN | Prometheus 抓取目标 |
| `GET /actuator/metrics/**` | ADMIN | 单指标查询 |

### 自定义业务指标

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `soulous_llm_calls_total` | counter | provider, model, outcome | LLM 调用计数 |
| `soulous_llm_latency` | timer | provider, model | LLM 延迟分布 |
| `soulous_rate_limit_blocked_total` | counter | rule | 限流命中次数 |
| `soulous_moderation_verdict_total` | counter | verdict, target | 内容审核裁定（PASS/FLAG/BLOCK × INPUT/OUTPUT） |
| `soulous_storage_gc_deleted_total` | counter | — | GC 删除（或 dry-run "本应删除"）对象数 |
| `soulous_refresh_token_replayed_total` | counter | — | refresh-token 复用警报次数 |
| `soulous_notification_pushed_total` | counter | type | 通知推送数 |

可选 HealthIndicator（默认关闭）：

- `SOULOUS_HEALTH_STORAGE_CHECK_ENABLED=true` — 对象存储读写探活（本地 + S3 通用）
- `SOULOUS_HEALTH_LLM_CHECK_ENABLED=true` — LLM 探活；mock provider 返回 unknown，真 provider 会消耗一次最小请求

---

## 配置开关速查

| 环境变量 | 默认 | 作用 |
| --- | --- | --- |
| `SOULOUS_FLYWAY_ENABLED` | `true` | 迁移总开关 |
| `SOULOUS_JPA_DDL_AUTO` | `update`(dev) / `validate`(prod) | Hibernate DDL 行为 |
| `SOULOUS_RATE_LIMIT_ENABLED` | `true` | 限流总开关 |
| `SOULOUS_STORAGE_GC_ENABLED` | `true` | 凭证文件 GC |
| `SOULOUS_STORAGE_GC_DRY_RUN` | `true` | GC 只 log 不真删（观察期建议保持 true） |
| `SOULOUS_STORAGE_GC_MIN_AGE_HOURS` | `24` | 多久之前的文件可被回收 |
| `SOULOUS_STORAGE_GC_CRON` | `0 0 3 * * *` | GC 触发时间 |
| `SOULOUS_RAG_ENABLED` | `false` | RAG 总开关（开启需先配好 embedding provider） |
| `SOULOUS_RAG_TOP_K` | `3` | 单次召回最多注入 prompt 的命中数 |
| `SOULOUS_RAG_MIN_SIMILARITY` | `0.65` | cosine 阈值；低于此分的命中丢弃 |
| `SOULOUS_RAG_HALF_LIFE_DAYS` | `90` | RAG 半衰期；`0` 关闭衰减 |
| `SOULOUS_EMBEDDING_PROVIDER` | `mock` | `mock` / `ollama` / `openai` / `google` (`gemini` 等价) |
| `SOULOUS_EMBEDDING_MODEL` | — | 如 `nomic-embed-text`（Ollama）、`text-embedding-3-small`（OpenAI） |
| `SOULOUS_EMBEDDING_BASE_URL` | — | Ollama / OpenAI 兼容 endpoint |
| `SOULOUS_EMBEDDING_API_KEY` | — | OpenAI 路径必填；自托管 Ollama 留空 |
| `SOULOUS_EMBEDDING_DIMENSION` | `768` | 必须与所选模型一致，否则旧向量会因维度不符被跳过 |
| `SOULOUS_NOTIFICATION_EMAIL_ENABLED` | `false` | 邮件 sink 开关，开启后还需配置 `spring.mail.host/port/username/password` |
| `SOULOUS_NOTIFICATION_EMAIL_FROM` | `no-reply@soulous.local` | 发件人地址 |
| `SOULOUS_NOTIFICATION_EMAIL_SUBJECT_PREFIX` | `[Soulous] ` | 主题前缀 |
| `SOULOUS_JWT_ACCESS_TTL_SECONDS` | `3600` | access JWT 有效期（秒） |
| `SOULOUS_JWT_REFRESH_TTL_DAYS` | `30` | refresh token 有效期（天） |
| `SOULOUS_JWT_SECRET` | dev-only | **生产必须覆盖** |
| `SOULOUS_AUDIT_ENABLED` | `true` | 通用审计开关 |
| `SOULOUS_CAPTCHA_ENABLED` | `true` | 登录 / 注册验证码 |
| `SOULOUS_HEALTH_DETAILS` | `never` | `/actuator/health` 是否返回 details |
| `SOULOUS_LLM_PROVIDER` | `mock` | `mock` / `openai` / `anthropic` |
| `SOULOUS_LLM_BASE_URL` / `SOULOUS_LLM_API_KEY` / `SOULOUS_LLM_MODEL` | — | LLM 接入参数 |
| `SOULOUS_BOOTSTRAP_ADMIN_USERNAME/PASSWORD/NICKNAME` | — | 首个管理员 bootstrap |

---

## 项目结构

```text
backend/                 Spring Boot + JPA API
  src/main/java/com/soulous/
    aisession/           AI 拆解会话（Planning Session）
    goal/                长期目标
    ...                  其余领域服务、Controller、安全
  src/main/resources/
    application.yml
    db/migration/{h2,mysql}/V*.sql   Flyway 脚本
frontend/                React + Vite + TypeScript
  src/pages/             业务页面
  src/components/        共享组件 + 懒加载图表
  public/pets/           宠物 sprite 资源
docs/                    架构 / 接口 / 数据库 / 部署 / 用户手册
DEPLOY.md                生产部署清单（env / HTTPS / 反代 / MySQL / S3 / LLM）
```

---

## 通知系统：SSE 推送 + 邮件外推

实时层走 SSE，离线 / 长时间未上线的事件再由邮件兜底。

### 反代注意事项（nginx 例）

SSE 是长连接，反代默认的 buffering 会让事件卡住。除了应用层已经写的 `X-Accel-Buffering: no`，nginx 上也建议：

```nginx
# 通知推送 + AI 拆解对话流式回复，都是 SSE，用同一套配置
location ~ ^/api/(notifications/stream|ai/sessions/[0-9]+/messages/stream)$ {
    proxy_pass http://localhost:8080;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;   # 大于应用 EMITTER_TTL_MS (30 min)
    chunked_transfer_encoding on;
}
```

### AI 拆解对话流式输出

`POST /api/ai/sessions/{id}/messages/stream` 走 `text/event-stream`，emit 三种事件：

- `event: token` — 增量文本片段，`data` 为 JSON 编码的字符串
- `event: done` — 流结束，`data` 为完整 SessionView JSON（含 plan envelope / state 转移结果）
- `event: error` — 失败，`data` 为消息字符串

前端用 `fetch + ReadableStream` 解析（EventSource 不支持 POST + body）。OpenAI 兼容的 provider（DeepSeek/通义/Moonshot/Ollama）自动用 SSE chat completion；其它 provider 走单次响应再"假流式"一次性吐出，UI 体感一致但实际不是逐 token。

**Output moderation 与流式的取舍**：当前实现是"先流出去，流完再做 output moderation"。如果 moderation 命中 BLOCK，前端会在 `done` 事件里收到替换后的拒绝文案，气泡内容会从流式片段切换到正式回复——短暂出现"被回收"的内容是已知 tradeoff，换来了打字机体感。要严格隐藏可疑内容请关掉 streaming。

### 启用邮件（可选）

```bash
export SOULOUS_NOTIFICATION_EMAIL_ENABLED=true
export SPRING_MAIL_HOST=smtp.example.com
export SPRING_MAIL_PORT=587
export SPRING_MAIL_USERNAME=apikey
export SPRING_MAIL_PASSWORD=<secret>
export SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
export SOULOUS_NOTIFICATION_EMAIL_FROM=alerts@yourdomain.com
```

未启用时 `EmailNotificationSink` 不会被创建，`spring.mail.*` 也不需要配置。
启用后用户必须在「资料」页填了邮箱才会收到邮件——未填邮箱的用户照样在站内铃铛收到通知。

---

## RAG 长时记忆：小 VPS 自托管 Ollama 步骤

```bash
# 1) VPS 上一键装 Ollama，并拉一个 768 维 embedding 模型
curl -fsSL https://ollama.com/install.sh | sh
ollama pull nomic-embed-text       # 768 维，体积小，CPU 也能跑

# 2) 让 Ollama 监听非本机端口（默认仅 127.0.0.1），通过 systemd drop-in 或环境变量都可
sudo systemctl edit ollama
# 在编辑器里写入：
#   [Service]
#   Environment="OLLAMA_HOST=0.0.0.0:11434"
sudo systemctl restart ollama

# 3) Soulous 后端环境变量
export SOULOUS_RAG_ENABLED=true
export SOULOUS_EMBEDDING_PROVIDER=ollama
export SOULOUS_EMBEDDING_BASE_URL=http://<vps-ip>:11434
export SOULOUS_EMBEDDING_MODEL=nomic-embed-text
export SOULOUS_EMBEDDING_DIMENSION=768

# 4) 把当前登录用户已有的目标/对话/已完成任务一次性灌入向量库
#    （登录拿到 JWT 后调用；首次开启 RAG 或换模型/维度时跑一次）
curl -X POST -H "Authorization: Bearer <jwt>" http://localhost:8080/api/rag/reindex
```

> 内网安全：Ollama 没有鉴权，公网暴露端口前请用 nginx 加 Basic Auth / IP 白名单 / WireGuard 任选其一。
> 切换 embedding 模型后维度会变；老向量因维度不符被自动跳过，跑一次 backfill 即可重建。

### 用 Google Gemini Embedding API（无须 VPS）

不想自己跑 Ollama 也可以走 Google 的托管 embedding。`gemini-embedding-001` 维度可在 768/1536/3072 中选，`text-embedding-004` 固定 768。

```bash
# 1) 在 https://aistudio.google.com/ 拿一个 API key（个人免费额度对个人用足够）

# 2) 后端环境变量
export SOULOUS_RAG_ENABLED=true
export SOULOUS_EMBEDDING_PROVIDER=google
export SOULOUS_EMBEDDING_API_KEY=<your-google-api-key>
export SOULOUS_EMBEDDING_MODEL=text-embedding-004     # 或 gemini-embedding-001
export SOULOUS_EMBEDDING_DIMENSION=768                # text-embedding-004 固定 768；gemini-embedding-001 可填 768/1536/3072
# 可选：换基地址（默认 https://generativelanguage.googleapis.com）
# export SOULOUS_EMBEDDING_BASE_URL=https://generativelanguage.googleapis.com

# 3) 自检：直接 curl 看一下能不能通
curl -sS "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent" \
  -H "content-type: application/json" \
  -H "x-goog-api-key: $SOULOUS_EMBEDDING_API_KEY" \
  -d '{"model":"models/text-embedding-004","content":{"parts":[{"text":"hello"}]}}' \
  | head -c 200

# 4) 登录后灌库
curl -X POST -H "Authorization: Bearer <jwt>" http://localhost:8080/api/rag/reindex
```

> 国内访问：`generativelanguage.googleapis.com` 在大陆通常需要代理。可以把后端机器整体走代理，或者用 `SOULOUS_EMBEDDING_BASE_URL` 指向你自己的反代域名（反代再走出墙）。
> 鉴权方式：本实现走 `x-goog-api-key` Header，避免 key 出现在 URL query 里。

---

## 文档

- [架构](docs/architecture.md)
- [接口](docs/api.md)
- [数据库](docs/database.md)
- [AI 审核规则](docs/ai-review-rules.md)
- [用户手册](docs/user-guide.md)
- [部署](docs/deployment.md) / [DEPLOY.md](DEPLOY.md)
- [Claude 交接](docs/handoff-to-claude.md)
