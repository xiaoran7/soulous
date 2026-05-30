# Claude 交接说明

这份文档给接手 Soulous MVP 的 Claude 使用。请先读本文件，再按需查看 `README.md`、`docs/api.md`、`docs/architecture.md`、`docs/database.md`、`docs/user-guide.md`。

最近一次更新：2026-05-16。

> **注意**：本文件容易过时。**真实状态以 `git status` + `mvn test` + `npm test` 实跑结果为准**，本文件只描述设计意图与高层结构。

## 当前状态

项目已经从空目录搭成可运行的全栈 MVP：

- 后端：`backend/`，Spring Boot 3 + Java 21 + Maven + Spring Data JPA + Spring Security + JWT。
- 前端：`frontend/`，React 19 + Vite 6 + TypeScript + Vitest。
- 数据库：默认 H2 文件库，MySQL profile 已配置。
- AI：可插拔 LLM 客户端（`LlmService`），支持 `mock`（规则模拟，默认）/ `anthropic`（Claude）/ `openai`（兼容 DeepSeek、Moonshot 等）。任何 LLM 调用失败自动回退规则版。
- 文档：`docs/` 下已有架构、接口、数据库、部署、用户手册、AI 审核规则。

```text
backend  http://localhost:8080
frontend http://localhost:5173
```

默认账号：

```text
普通用户：demo / demo123
管理员：admin / admin123
```

## 已实现功能

用户侧：

- 注册、登录、读取和更新个人资料。
- 任务创建、编辑、归档（软删除）、恢复、开始。
- 学习凭证提交：文字、学习时长、代码片段、链接、截图 URL。
- 截图上传：`POST /api/files/screenshots`，本地保存并通过 `/uploads/**` 访问。
  - 限制：最大 5MB；MIME 白名单 jpeg/png/gif/webp；扩展名白名单。
- 提交后同步触发模拟 AI 审核。
- 用户可查看提交记录，并打开单条"审核反馈"，看到 AI 分数、原因、建议、关联任务以及管理员复核意见（`adminComment` 字段）。
- 被打回或要求补充的提交可发起申诉。
- AI 任务拆解：输入学习目标，生成可加入任务列表的任务。
- AI 每日复盘：根据今日任务、提交、学习时长、经验日志、宠物状态生成总结。
- 宠物成长页：宠物状态、动作预览、成长事件日志。
- 统计页：今日指标、近 7 天趋势、课程分布。
- 自习室：选场景 + 环境音/音乐 + **正计时**专注（非倒计时，时间往上加），点进入即全屏沉浸（隐藏侧栏/顶栏，抽屉唤回）；支持自定义上传场景图/音乐。后端会话沿用开始/暂停/继续/完成/中止。

管理员侧：

- 查看提交队列与详情。
- 查看 AI 总分、相关性、完整度、质量、建议经验。
- 人工通过、要求补充、驳回（均带复核意见、可自定义经验值）。
- 处理申诉：通过、要求补充、驳回。

## 关键文件

后端（`backend/src/main/java/com/soulous/`）：

- `Entities.java`：JPA 实体。`UserAccount.token` 字段已废弃，由 JWT 替代。
- `Enums.java`：任务、提交、AI、宠物、申诉等枚举。
- `Dto.java`：请求/响应 record。
- `Repositories.java`：JPA repository。
- `Services.java`：核心服务，含 `JwtService`、`UserService`、`TaskService`、`AiService`、`PetService`、`FileStorageService`、`AdminService`、`AppealService`、`StatsService`、`FocusService`。
- `DailyReviewService.java`：每日复盘服务和 `/api/ai/daily-review` 控制器（接入 LLM，失败时使用规则版兜底）。
- `LlmService.java`：LLM 抽象层。`provider=mock` 不发请求；`anthropic` / `openai` 走 java.net.http。`completeJson` 自动清掉 Markdown 围栏。
- `PetGrowthRules.java`：宠物成长状态规则。
- `Controllers.java`：REST API 控制器。
- `Support.java`：异常、`SecurityConfig`（Spring Security filter chain + CORS）、`JwtAuthenticationFilter`、默认账号初始化、H2 兼容迁移、静态上传映射。

测试：

- `backend/src/test/java/com/soulous/`：50 个测试（SoulousApplicationTests 8 / PetGrowthRulesTests 5 / PasswordPolicyTests 10 / LlmServiceTests 12 / AiServiceTests 13 / ProdProfileTests 2）。具体数量以 `mvn test` 输出为准。

前端（`frontend/src/`）：

- `main.tsx`：应用外壳，仅 174 行；按 page 调度子页面。
- `pages/`：`AuthScreen`、`Dashboard`、`TasksPage`、`TimetablePage`、`PlannerPage`、`DailyReviewPage`、`PetPage`、`StatsPage`、`FocusPage`、`ProfilePage`、`AdminPage`。
- `components/shared.tsx`：跨页面共享组件（NavButton、Metric、TaskRow、PetCard、Empty、ProgressRing、SidebarPet、animationForPet 等）。
- `components/TrendChart.tsx`：Recharts 趋势图，独立 chunk，被 Dashboard 和 StatsPage 通过 `React.lazy` 懒加载。
- `api.ts`：API 客户端。
- `types.ts`：前端类型。
- `PetSprite.tsx`：宠物 spritesheet 动画组件。
- `styles.css`：全局样式。
- `__tests__/`：Vitest + @testing-library/react 测试，覆盖 AuthScreen 和 PlannerPage。

文档：

- `README.md`、`docs/api.md`、`docs/architecture.md`、`docs/database.md`、`docs/ai-review-rules.md`、`docs/user-guide.md`、`docs/deployment.md`。

## 运行命令

后端：

```bash
cd backend
mvn spring-boot:run
```

前端：

```bash
cd frontend
npm install
npm run dev
```

验证：

```bash
cd backend
mvn test          # 当前 50 测试

cd ../frontend
npm test          # 当前 14 测试 (Vitest)
npm run build     # 应无 chunk size 警告
```

最近一次本地验证（2026-05-16）：

- `mvn test`：50 通过。
- `npm test`：14 通过。
- `npm run build`：之前通过，无 chunk size 警告。主 bundle 约 266 KB，TrendChart 独立 chunk 约 394 KB（首次进入统计/工作台页才下载）。

## 重要 API

用户：

- `POST /api/auth/register`、`POST /api/auth/login` —— 返回 JWT，前端存到 `localStorage.soulous_token`。
- `GET /api/users/me`、`PUT /api/users/me`

任务/提交：

- `GET /api/tasks`、`POST /api/tasks`、`PUT /api/tasks/{id}`
- `GET /api/tasks/archived`、`POST /api/tasks/{id}/restore`
- `DELETE /api/tasks/{id}` —— 软归档（写 `archivedAt`），不会硬删
- `POST /api/tasks/{id}/start`、`POST /api/tasks/{id}/submit`
- `GET /api/submissions/my`、`GET /api/submissions/{id}`

AI：

- `GET /api/ai/info` —— 返回 `{ provider, available, model }`，前端 PlannerPage 显示当前 AI 状态。
- `POST /api/ai/decompose`、`POST /api/ai/review`、`POST /api/ai/daily-review`
- `POST /api/ai/question/answer`
- `GET /api/admin/llm-stats` —— 仅 admin，返回 `{ totalCalls, cacheHits, successes, failures, cacheSize, cacheEnabled, lastFailure* }`。

宠物/统计：

- `GET /api/pet`、`POST /api/pet/feed`、`GET /api/pet/logs`
- `GET /api/stats/summary`

专注：

- `GET /api/focus/sessions`、`GET /api/focus/active`
- `POST /api/focus/sessions`、`POST /api/focus/sessions/{id}/{pause|resume|finish}`

申诉/管理：

- `POST /api/appeals`、`GET /api/appeals/my`
- `GET /api/admin/submissions`、`GET /api/admin/submissions/{id}`
- `POST /api/admin/submissions/{id}/{approve|reject|need-more}`
- `GET /api/admin/appeals`、`POST /api/admin/appeals/{id}/review`

文件：

- `POST /api/files/screenshots`（multipart）

## 设计和实现约定

- 认证：Spring Security + 无状态 JWT。**默认走 httpOnly cookie `soulous_token`**（SameSite=Lax，path=/，7 天）。`JwtAuthenticationFilter` 优先读 `Authorization: Bearer <jwt>`，否则读 cookie。前端 `fetch` 全部带 `credentials: 'include'`，登录/注册时后端通过 `Set-Cookie` 发放。Cookie httpOnly，JS 无法读取，登出走 `POST /api/auth/logout` 让服务器清 cookie。`UserService.byToken` 解析 JWT 并按 id 查询用户。`BaseController.current(request)` 从 SecurityContext 取出。
- 密码：BCrypt 哈希。`ensureUser` 在启动时为旧明文密码自动重新哈希。
- JWT 配置：`soulous.jwt.secret`（环境变量 `SOULOUS_JWT_SECRET` 覆盖，生产必须改），`soulous.jwt.ttl-seconds` 默认 7 天。Cookie 是否 `Secure` 由 `soulous.cookie.secure`（默认 false，生产应设 true）控制。
- 公开端点：`/api/auth/**`、`/h2-console/**`、`/error`。`/uploads/**` 已要求认证（cookie 在 `<img src>` / 新标签页打开时由浏览器自动带上）。所有 `/api/admin/**` 要求 `ROLE_ADMIN`，其余 `/api/**` 需登录。
- 默认数据库是 H2 文件库：`backend/data/soulous`。
- 测试使用独立内存 H2，避免污染本地开发数据。
- 截图文件默认保存到 `backend/uploads`，文件名 `<UUID><ext>`。
- AI 审核：`AiService.review` 优先调 LLM（结构化 JSON），失败/未配置时回退到规则版（`ruleBasedReview`）。同样适用于 `generateQuestion` 和 `decompose`。
- 每日复盘：`DailyReviewService.generate` 数值指标始终走规则版，文本字段（title/summary/highlights/risks/tomorrowSuggestions/petMessage）优先用 LLM 输出，回退规则版。
- LLM 配置：`soulous.llm.provider=mock|anthropic|openai`，`soulous.llm.api-key`，`soulous.llm.model`，`soulous.llm.base-url`（OpenAI-compatible 端点）。环境变量 `SOULOUS_LLM_*` 覆盖。`mock` 模式从不发网络请求，所有 AI 输出都用规则版。
- 宠物成长规则集中在 `PetGrowthRules`，服务层只负责调用规则、保存日志。
- 前端不再使用 localStorage 存 token；登录状态通过尝试调用 `/api/users/me` 探测（401 即未登录，由 `UnauthorizedError` 区分于其它错误）。`api.ts` 所有 fetch 均带 `credentials: 'include'`。
- TrendChart（Recharts）懒加载，独立 chunk。
- 前端测试：Vitest + jsdom + @testing-library/react，配置在 `vitest.config.ts`，setup 在 `src/test-setup.ts`。

## 已知问题和注意事项

- 截图 URL `/uploads/<UUID>` 现已要求认证。开发模式通过 Vite proxy 把 `/uploads/**` 转发到后端，cookie 同源；生产部署需要前后端同域或开启 SameSite=None+Secure（HTTPS）。
- LLM `provider=mock` 是默认值，所有 AI 行为与之前一致。生产启用真实 LLM 需要至少 `SOULOUS_LLM_PROVIDER` + `SOULOUS_LLM_API_KEY`，可选 `SOULOUS_LLM_MODEL` 和 `SOULOUS_LLM_BASE_URL`。
- **本机当前接通 DeepSeek**（Windows 用户级环境变量）：`SOULOUS_LLM_PROVIDER=openai`、`SOULOUS_LLM_BASE_URL=https://api.deepseek.com`（不带 `/v1`，LlmService 自己拼）、`SOULOUS_LLM_MODEL=deepseek-chat`、`SOULOUS_LLM_API_KEY=<key>`。改环境变量后需重启 PowerShell/IDE/后端。验证接通：PlannerPage 标题旁显示 `OpenAI 兼容 · deepseek-chat`。
- 已提交过的任务不能硬删除，DELETE 实际是归档（写 `archivedAt`）。任何级联行为务必谨慎。
- H2 文件库在反复开发和重启后可能残留测试/烟测数据。需要干净演示时，可停止后端后清理 `backend/data` 和 `backend/uploads`。
- `SecurityConfig` 同时启用了 H2 console 路径放行和 `frameOptions().disable()`，仅开发可用。**生产用 `--spring.profiles.active=prod` 一键收紧**：`soulous.cookie.secure=true`、H2 console + frameOptions 关闭、`/h2-console/**` 不再 permitAll、CORS 不再带 localhost 兜底、demo/admin 默认账号不再播种；启动时若仍用默认 JWT secret 直接抛错。开关在 `soulous.security.h2-console-enabled` / `soulous.security.dev-origins-enabled` / `soulous.seed-default-users`。
- `UserAccount.token` 字段已经从实体里删除，启动迁移 `DROP COLUMN IF EXISTS token` 会清掉旧 H2 列；旧 JWT 在 secret 不变的情况下仍然有效。
- 文件上传强制 5MB / MIME / 扩展名白名单（jpeg/png/gif/webp）；Spring multipart 上限 5MB / 单请求 6MB。
- `application.yml` 默认 secret 仅供开发，生产请用 `SOULOUS_JWT_SECRET` 环境变量。

## 推荐下一步

按价值排序（已落地的项目不再列出，详见上面"已实现功能"和 memory）：

1. **CSRF 防护**：当前 CSRF 已 disable，依赖 SameSite=Lax + 同源前端。未来支持不同域前端或第三方嵌入时需要加 CSRF token。
2. **JWT 失效/续签策略**：当前 7 天硬过期。需要"踢人下线"或"修改密码后所有 token 失效"时扩展。
3. **Pet/Daily review 的图形化升级**：当前仅文字 + spritesheet。
4. **上传切对象存储**：S3/OSS，生产化。

已落地（不要重复做）：LLM 抽象 + 三 provider、LLM 缓存+遥测+`/api/admin/llm-stats`、密码强度、管理员审计日志、prod profile 收紧、Vitest 测试、Recharts 懒加载。

## 交接提醒

如果 Claude 要继续改代码，请先运行：

```bash
git status --short
cd backend && mvn test
cd ../frontend && npm test && npm run build
```

仓库目前仍是 staged-but-not-committed 的初始状态，`git status` 中会看到大量 `A` / `AM` 文件，这是正常的。不要随意 reset 或 checkout，避免丢掉已有实现。
