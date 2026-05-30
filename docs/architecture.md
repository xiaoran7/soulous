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
  └── /*, /assets/**       → React SPA (dist/)
```

- **frontend**：React 19 + Vite + TypeScript，SPA，页面路由：工作台 / 任务 / AI 拆解 / 宠物 / 统计 / 管理后台。
- **backend**：Spring Boot 3 + Java 21 + JPA + Spring Security，提供业务 REST API；feature-package 拆分（`com.soulous.{admin,auth,focus,goal,task,pet,...}`）。
- **DB**：默认 H2 文件库（`backend/data/soulous.mv.db`），可切 MySQL。**Flyway 管理迁移**（h2 / mysql 各一套，`db/migration/{vendor}/V*.sql`）。

---

## 核心业务流

```
创建任务 → 开始任务 → 上传截图/填写凭证 → 提交
  → AI 初审（LLM 或规则兜底）
      ├── APPROVED：写 exp_log，宠物增长
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
- 登录/注册强制图形验证码（`GET /api/auth/captcha` → base64 SVG，120s 有效一次性）

---

## 主要模块

| 包 | 职责 |
|---|---|
| `auth` | 注册 / 登录 / refresh / logout / 改密；双 token；PasswordPolicy |
| `task` | 任务 CRUD、开始、提交凭证、AI 初审、内容审核 |
| `focus` | 专注计时会话，关联到 task |
| `goal` | 学习目标管理 |
| `aisession` | AI 对话式拆解目标，RAG 增强上下文 |
| `pet` | 宠物成长、经验、心情；心情加权 EXP |
| `stats` | 今日指标、近 7 天趋势、课程占比 |
| `review` | 管理员提交队列、人工复核 |
| `appeal` | 内容审核误拦申诉流程 |
| `notification` | 站内通知（AI 审核 / 申诉事件），前端 `NotificationBell` |
| `audit` | 统一 `audit_log`：login/logout/password/refresh-replay/admin 操作 |
| `moderation` | 内容审核（fast-path + LLM，默认关） |
| `rag` | 历史打卡 RAG（embedding + cosine + 时间衰减，默认关） |
| `storage` | 本地 / S3 兼容双后端，上传压缩 + 夜间 GC |
| `ai` | 可插拔 LlmService：mock / anthropic / openai-compatible；LRU+TTL 缓存；失败遥测 |

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
