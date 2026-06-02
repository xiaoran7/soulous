# API 说明

Base URL: `http://localhost:8080`

> **Web 与 Mobile 鉴权差异**
> - Web：双 token JWT，access (1h) + refresh (30d) 都写 HttpOnly cookie，请求自动带 cookie。登录/注册需先取 CAPTCHA（`GET /api/auth/captcha`）。
> - Mobile：见下方 [Mobile Auth](#mobile-auth)，refresh token 走 response body，由客户端存 Keychain/Keystore，无 CAPTCHA（靠限流 + 未来设备 attestation）。
> - 兼容 `Authorization: Bearer <token>`：web 端如果 cookie 没带（跨域场景）也认 Authorization 头。

完整 Phase 1/2 能力（限流、审计、通知、SSE、RAG）见根目录 `README.md`；这里只列 REST 端点速查。

## Auth/User

### `POST /api/auth/register`

```json
{
  "username": "alice",
  "password": "pass123",
  "nickname": "Alice",
  "email": "alice@example.com"
}
```

### `POST /api/auth/login`

```json
{
  "username": "demo",
  "password": "demo123"
}
```

### `GET /api/users/me`

返回当前用户资料。

### `PUT /api/users/me`

更新昵称、邮箱、头像。

```json
{
  "nickname": "Alice",
  "email": "alice@example.com",
  "avatarUrl": "https://example.com/avatar.png"
}
```

## Mobile Auth

Native 客户端（Android Flutter）专用。refresh token 走 body，不写 cookie；无 CAPTCHA。

### `POST /api/auth/mobile/register`

```json
{ "username": "alice", "password": "pass1234", "confirmPassword": "pass1234", "nickname": "Alice" }
```

### `POST /api/auth/mobile/login`

```json
{ "username": "alice", "password": "pass1234" }
```

返回：

```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<opaque>",
  "accessExpiresIn": 3600,
  "user": { "id": 1, "username": "alice", "nickname": "Alice", "role": "USER", ... }
}
```

客户端把 `refreshToken` 存安全存储（Keychain / Keystore），在 access 过期前几分钟用它换新 access。

### `POST /api/auth/mobile/refresh`

```json
{ "refreshToken": "<opaque>" }
```

返回新的 `{ accessToken, refreshToken, accessExpiresIn }`（refresh token 自动 rotate，旧的失效）。同一 refresh token 被使用两次会触发 replay 检测，吊销该用户全部 refresh token。

### `POST /api/auth/mobile/logout`

```json
{ "refreshToken": "<opaque>" }
```

吊销当前 refresh token。客户端同时清掉本地 access + refresh。

## AI 拆解对话（Chat）/ SSE

Gemini 式的分类 → 对话 → 消息聊天（取代旧的目标中心 Planner）。聊天里 AI 可输出 `<PLAN_JSON>` 计划草案，确认后落地为真实 `StudyTask`（不挂目标，`goalId` 为空）。附件（md/pdf/txt）由前端提取文本后拼进消息正文，无独立上传端点。

分类与对话：
- `GET /api/chat/tree` — 侧边栏树：全部分类 + 全部对话摘要（`categoryId` 为 null = 默认组）
- `POST /api/chat/categories`、`PATCH /api/chat/categories/{id}`、`DELETE /api/chat/categories/{id}` — 分类增改删（删除后其下对话归默认组，不删对话）
- `POST /api/chat/conversations`（可选 `{categoryId}`）、`GET /api/chat/conversations/{id}`、`PATCH /api/chat/conversations/{id}`（`{title?, categoryId?, clearCategory?}` 重命名/移动）、`DELETE /api/chat/conversations/{id}`

消息与计划：
- `POST /api/chat/conversations/{id}/messages` — 发消息（非流式）
- `POST /api/chat/conversations/{id}/messages/stream` — 多轮对话，`text/event-stream`，事件：`token`（增量）/ `done`（含完整 `ConversationView`）/ `error`
- `PATCH|DELETE /api/chat/conversations/{id}/plan/tasks/{index}` — 编辑/删除计划草案中的单条任务
- `POST /api/chat/conversations/{id}/commit` — 把 pendingPlan 落地为真实 `StudyTask`

涉及 LLM 的端点配置小时级（60/h）+ 天级（200/d）限流（按用户）。

> 旧的 `/api/ai/sessions/*`（PlanningSession）与 `/api/goals/*`（Goal）REST 已下线删除；底层 `goal` / `planning_session` 表与服务暂时保留（RAG、每日复盘仍引用），但不再有对外接口。

> SSE 服务端依赖 `spring.mvc.async.request-timeout`（默认 300000ms = 5min），反代如 nginx 需关 `proxy_buffering` + 抬 `proxy_read_timeout`，否则 token 流被攒在反代里或被切断。

## Notifications / SSE

- `GET /api/notifications` — 列表（分页）
- `POST /api/notifications/{id}/read` / `POST /api/notifications/read-all`
- `GET /api/notifications/stream` — `text/event-stream` 实时推送 AI 审核 / 申诉等事件，前端 NotificationBell 优先 SSE，断流 fallback 60s 轮询

## Tasks

### `GET /api/tasks`

返回当前用户任务列表。

### `POST /api/tasks`

```json
{
  "title": "复习数据结构栈和队列",
  "description": "整理关键概念并完成一道练习",
  "taskType": "STUDY",
  "difficulty": "NORMAL",
  "courseName": "数据结构",
  "priority": 1,
  "estimatedMinutes": 40,
  "baseExp": 30
}
```

### `POST /api/tasks/{id}/start`

将任务置为 `DOING` 并记录开始时间。

### `POST /api/tasks/{id}/submit`

```json
{
  "textProof": "我复习了栈的后进先出、队列的先进先出，并整理了循环队列判满条件。",
  "studyMinutes": 35,
  "codeSnippet": "",
  "proofLink": "",
  "screenshotUrl": "/uploads/example.png"
}
```

提交后自动触发模拟 AI 审核，返回任务、提交和审核结果。

## Submissions / Files

### `GET /api/submissions/my`

返回当前用户的提交记录，用于查看审核状态和发起申诉。

### `GET /api/submissions/{id}`

返回当前用户某条提交的任务与 AI 审核反馈。普通用户只能查看自己的提交，管理员可查看任意提交。

```json
{
  "submission": {
    "id": 1,
    "status": "AI_REJECTED",
    "studyMinutes": 5
  },
  "task": {
    "id": 1,
    "title": "复习数据结构栈和队列"
  },
  "review": {
    "result": "REJECT",
    "score": 31,
    "reason": "提交内容过短，无法体现具体学习过程。",
    "suggestion": "请补充学习内容、知识点、练习结果或截图凭证。",
    "recommendedExp": 0
  }
}
```

### `POST /api/files/screenshots`

上传用户主动选择的截图凭证。请求类型为 `multipart/form-data`，字段名为 `file`。

返回：

```json
{
  "url": "/uploads/4b3e0d6e-....png"
}
```

上传后的 URL 可作为任务提交的 `screenshotUrl`。

## AI

### `POST /api/ai/decompose`

```json
{
  "goal": "我要复习数据结构的栈和队列"
}
```

返回可加入计划的任务列表。

### `POST /api/ai/review`

MVP 中审核随任务提交自动触发，此接口返回说明消息。

### `POST /api/ai/daily-review`

根据当前用户今日任务、提交、学习时长、经验日志和宠物状态生成每日复盘。

返回：

```json
{
  "date": "2026-05-14",
  "title": "今天的学习闭环已经形成",
  "summary": "今天提交了 2 次学习凭证...",
  "highlights": ["今天完成了 1 个已审核通过的学习任务。"],
  "risks": ["今天节奏稳定，继续保持具体、可验证的学习凭证。"],
  "tomorrowSuggestions": ["继续推进未完成任务。"],
  "petMessage": "宠物今天获得了成长能量。",
  "metrics": {
    "completedTasks": 1,
    "submissions": 2,
    "studyMinutes": 45,
    "earnedExp": 30,
    "petLevel": 1,
    "petStatus": "HAPPY"
  }
}
```

## Timetable / 课表

用户课表（一周课程网格），供前端展示。导入走 LLM 解析：前端把教务系统导出的 `.xls`（用 SheetJS 在浏览器内转成 HTML 表格）或直接粘贴的课表 HTML 交给后端解析。全部接口需登录。（注：课表作为 `[COURSES]` 背景注入 AI 拆解的旧逻辑随 2026-06-01 的对话重构已移除。）

### `GET /api/timetable?semester=`

列出当前用户课表，可选 `semester` 过滤。

### `POST /api/timetable/import`

LLM 解析课表 HTML 落库，按用户限流 30/h、100/day。

```json
{ "html": "<table>...</table>", "semester": "2025-2026-2", "replace": true }
```

`replace=true` 时先清空旧课表（指定 `semester` 则只清该学期）。返回 `{ count, semester, courses }`。

### `POST /api/timetable`

手动新增一节课（不走 LLM）。`courseName` 与 `dayOfWeek`(1-7) 必填，其余可空；`weekParity` 为 `ALL`|`ODD`|`EVEN`。

```json
{ "courseName": "高等数学A2", "dayOfWeek": 1, "startSection": 1, "endSection": 2,
  "teacher": "刘老师", "location": "公共楼105", "startTime": "08:00", "endTime": "09:40",
  "weeks": "1-16", "weekParity": "ALL", "semester": "2025-2026-2" }
```

### `DELETE /api/timetable/{id}`

删除一节课（仅限本人）。

### `DELETE /api/timetable?semester=`

清空课表；带 `semester` 只清该学期，不带清全部。

## Pet/Stats

### `GET /api/pet`

返回当前用户宠物。

### `GET /api/pet/logs`

返回最近经验日志。

### `GET /api/stats/summary`

返回今日任务、今日经验、今日学习分钟、完成率、近 7 天趋势和课程分布。

## Appeals/Admin

### `POST /api/appeals`

```json
{
  "submissionId": 1,
  "appealReason": "我已补充学习说明，请人工复核。"
}
```

### `GET /api/appeals/my`

返回当前用户的申诉记录。

### `GET /api/admin/submissions`

管理员查看全部提交。

### `GET /api/admin/submissions/{id}`

管理员查看提交详情、任务、用户和 AI 审核结果。

### `POST /api/admin/submissions/{id}/approve`

```json
{
  "expAmount": 30,
  "comment": "人工确认通过"
}
```

### `POST /api/admin/submissions/{id}/reject`

```json
{
  "comment": "凭证不足"
}
```

### `POST /api/admin/submissions/{id}/need-more`

```json
{
  "comment": "请补充截图或更具体的学习总结"
}
```

### `GET /api/admin/appeals`

管理员查看全部申诉。

### `POST /api/admin/appeals/{id}/review`

```json
{
  "status": "APPROVED",
  "comment": "申诉通过，管理员已记录。"
}
```

`status` 可为 `APPROVED`、`REJECTED`、`NEED_MORE`。

### `GET /api/admin/audit`

最近 100 条管理员审计记录（approve / reject / need-more / appeal-review 各一行），按时间倒序。响应数组每项字段：

```json
{
  "id": 12,
  "adminId": 2,
  "adminUsername": "admin",
  "action": "APPROVE",
  "targetType": "SUBMISSION",
  "targetId": 305,
  "expAmount": 17,
  "resultStatus": "MANUAL_APPROVED",
  "comment": "人工确认补充材料有效",
  "createdAt": "2026-05-16T00:12:30"
}
```

### `GET /api/admin/submissions/{id}/audit`

返回指定提交的全部审计记录（同一个提交可能被多次复核）。
