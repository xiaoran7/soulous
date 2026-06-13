# 架构说明

> 生产部署细节见根目录 `DEPLOY.md`；API 参考见 `docs/api.md`；代码导览见 `docs/CODE_WALKTHROUGH.md`。

## 总体结构

Soulous 采用前后端分离：

```
浏览器
  │ HTTP(S)
  ▼
nginx（端口 80/443）
  ├── /api/**, /uploads/** → Spring Boot :8080
  │                            │ （soulous.agent.enabled=true 时）
  │                            └── agent-service :8100（Python LangGraph 认知边车，仅内网）
  └── /*, /assets/**       → React SPA (dist/)
```

- **frontend**：React 19 + Vite + TypeScript，SPA。未登录先看营销**落地页**（点 CTA 才进登录/注册）。UI 为「Luminous Ethereal」玻璃拟态：全局场景照片背景（SceneBackdrop）+ 顶部悬浮玻璃导航（无侧边栏），登录后默认落在自习室主页。页面路由：工作台（含每日签到卡）/ 任务 / 课表（固定本学期，成绩/考试页内分学期下拉）/ 复盘（含近 7 天趋势，原统计页已并入）/ 自习室（独享沉浸 + 共享在线房间）/ AI 拆解 / 宠物（含市场领养/购买/切换）/ 我的（金币/打卡/收藏/流水）/ 管理后台。
- **backend**：Spring Boot 3 + Java 21 + JPA + Spring Security，提供业务 REST API；feature-package 拆分（`com.soulous.{admin,auth,focus,goal,task,pet,...}`）。
- **DB**：默认 H2 文件库（`backend/data/soulous.mv.db`），可切 MySQL。**Flyway 管理迁移**（h2 / mysql 各一套，`db/migration/{vendor}/V*.sql`）。

---

## 核心业务流

```
创建任务 → 开始任务 → 上传截图/填写凭证 → 提交
  → AI 初审（本地 LLM 审核，不可用回退规则兜底；详见 docs/ai-review-rules.md）
      ├── APPROVED：写 exp_log，出战宠物增长，发放金币
      ├── NEED_MORE / AI_REJECTED：任务停留，用户可补交
      ├── MODERATION_BLOCKED：内容审核拦截，可申诉
      └── 人工复核：管理员 APPROVE / REJECT / NEED_MORE
```

---

## 认证

**双 token JWT**（Phase 1 落地）：

| Token | TTL | 存储 | 用途 |
|---|---|---|---|
| Access JWT | 1h | HttpOnly cookie | 每次 API 请求鉴权 |
| Refresh JWT | 30d | HttpOnly cookie（rotate） | 无感续期，存 `refresh_token` 表，可吊销 |

- 改密 / logout-all → `tokenVersion++`，旧 access token 立即失效
- Refresh replay 检测（同一 token 被使用两次视为泄露，立即吊销该用户全部 refresh token）
- **登录**强制图形验证码（`GET /api/auth/captcha` → base64 SVG，120s 有效一次性）
- **注册**改用邮箱验证码（`POST /api/auth/email-code` 发 6 位码 → register 提交 `emailCode`，邮箱必填）；未配 SMTP 时验证码打到后端日志（开发期回退）

---

## 主要模块

| 包 | 职责 |
|---|---|
| `auth` | 注册 / 登录 / refresh / logout / 改密；双 token；PasswordPolicy |
| `task` | 任务 CRUD、开始、提交凭证、AI 初审、内容审核 |
| `focus` | 专注计时会话，关联到 task |
| `chat` | **AI 拆解对话（当前）**：Gemini 式「分类 → 对话 → 消息」聊天，流式回复、`PLAN_JSON` 计划草案落地为 `StudyTask`（不挂目标）、`CLARIFY_JSON` 结构化追问、滚动摘要控长、输入/输出审核；附件 md/pdf/txt 由前端提取文本拼进消息 |
| `goal` / `aisession` | 旧的目标中心拆解（**已下线**，2026-06-01 重构）：REST 接口删除，表与服务暂留，仅 RAG 目标记忆 / 每日复盘 / `study_task.goal_id` 仍引用 |
| `timetable` | 课表：教务系统爬虫同步（`hut_schedule.py`，`--mode all` 一并抓课表 / 考试安排 / 成绩）、手动增删、按学期/周次组织；考试 `exam_entry`、成绩 `grade_entry` 分学期落库 |
| `pet` | 宠物成长、经验、心情；心情+streak 加权 EXP；**宠物市场**（品种目录 `PetSpecies` + 用户拥有多只 `owned_pet`，每宠独立等级、免费领养首只/金币购买/切换出战）；满级 Lv30、动作按等级解锁；断签衰减定时任务 |
| `wallet` | 金币系统：`user_account.coin_balance` + `coin_ledger` 流水；任务/专注/打卡赚币，购买宠物花币 |
| `checkin` | 每日打卡：发金币+给出战宠物经验，连续 streak 放大奖励 |
| `room` | 共享自习室（轻量在线状态，心跳轮询判在线，非 WebSocket）：建房/进房/退房/心跳/房主删房；广场列表惰性回收全员 30 分钟无心跳的僵尸房。前端建房/加入即启动真实专注会话进沉浸态 |
| `reminder` | 每日未打卡邮件提醒（`@Scheduled`，走通知→`EmailNotificationSink` 管道） |
| `stats` | 今日指标、近 7 天趋势、课程占比 |
| `review` | 管理员提交队列、人工复核 |
| `appeal` | 内容审核误拦申诉流程 |
| `notification` | 站内通知（AI 审核 / 申诉事件）；后端保留，前端铃铛入口已移除 |
| `audit` | 统一 `audit_log`：login/logout/password/refresh-replay/admin 操作 |
| `moderation` | 内容审核（fast-path + LLM，默认关） |
| `rag` | 历史打卡 RAG（embedding + cosine + 时间衰减，默认关） |
| `storage` | 本地 / S3 兼容双后端，上传压缩 + 夜间 GC |
| `ai` | 可插拔 LlmService：mock / anthropic / openai-compatible；LRU+TTL 缓存；失败遥测（现为 agent 不可用时的降级路径） |
| `agent` | **agent-service 边车对接**（2026-06-11）：`AgentClient`（HTTP/1.1 + SSE）把聊天/审核/复盘路由到 Python LangGraph 认知服务（仓库 `agent-service/`）；`/internal/**` 供 agent 回调（工具+moderation fast-path，service token 鉴权）；RAG 写入点异步推送 agent 向量库；`soulous.agent.enabled=false` 或 agent 故障时全部链路自动回退本地。详见 `agent-service/README.md` |

---

## 数据库

Flyway 迁移版本（h2 / mysql 各一套）：

| 版本 | 内容 |
|---|---|
| V1 | 全量基线（所有表、索引、外键） |
| V2 | 删 legacy `priority` 列 |
| V3 | 新增 `refresh_token` 表 |
| V4 | 新增 `notification` 表 |
| V5 | 新增 `audit_log` 表 |
| V6 | `task_submission` 状态枚举新增 `AI_REVIEWING` |
| V7 | 新增 `course_entry` 表（课表） |
| V8 | `study_task` 新增 `scheduled_weekday` 列 |
| chat / category | `chat_*` 三表 + `study_task.category`（mysql V9-V11 / h2 V9-V10，两套版本号已分叉） |
| exam / grade | 新增 `exam_entry` / `grade_entry` 表（考试安排 / 成绩）：h2 V11、mysql V12 |
| wallet | `user_account.coin_balance` 列 + `coin_ledger` 表：h2 V12、mysql V13 |
| checkin | `daily_checkin` 表：h2 V13、mysql V14 |
| pet market | `pet_species` + `owned_pet` 表（旧 `pet` 表保留不删，数据已迁入）：h2 V14、mysql V15 |
| study room | `study_room` + `room_member` 表：h2 V15、mysql V16 |

> 自 wallet 起 h2/mysql 版本号差 1（mysql 早期多一条 V9 `ai_turn_mediumtext`）。

> **注意（运维）**：prod 模式 `ddl-auto: validate`，新增实体字段必须同步写 `V{n}__*.sql`；否则启动时 Hibernate 校验失败。已有 VPS 上若漏写迁移，需手动 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`。
> 已应用的迁移文件**不要再改内容**，否则 checksum 变了 Flyway 启动时会校验失败（修法见 `docs/database.md`）。

---

## 可观测性

- `GET /actuator/health`、`/actuator/metrics`（prod 白名单）
- `GET /actuator/prometheus` → Micrometer Prometheus 格式指标
- 应用日志 → stdout（systemd journal / 容器）

---

## 限流

Bucket4j 应用层限流，auth 端点（登录/注册/refresh）+ AI 端点（decompose/submit）均有独立桶。
