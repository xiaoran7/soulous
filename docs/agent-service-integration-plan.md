# Agent Service 集成方案（通用骨架 → Soulous AI 全量替换）

> 状态：**已落地**（2026-06-11 起草并当日实施完成）。当前实现以 `agent-service/README.md` 为准；
> 本文保留设计决策与实施偏差记录。验收：agent-service pytest 26 项全绿、后端 238 项全绿、
> mock 全链路 e2e（登录→拆解对话→PLAN 落地 / SSE 流式 / 每日复盘）打通。

## 实施偏差（相对下文原方案）

1. **LangGraph SqliteStore 退役**：画像持久化归 DAL（agent_data.db），情景记忆统一入 sqlite-vec
   向量库（EPISODE 类型）——检索自带 embedding relevance，少一套存储引擎，多租户语义更清晰。
2. **回灌脚本改为 Spring 侧开关**：`SOULOUS_AGENT_BACKFILL=true` 启动一次后端即回灌
   （Python 读 H2 不现实，Java 读 memory_embedding 推 /rag/upsert 更稳）。
3. **工具集不含 create_task_draft**：计划落地仍走 PLAN_JSON 信封单一路径，避免双轨创建任务；
   工具为 query_focus_history / query_timetable / query_pet_status 三个只读查询。
4. **修了骨架两个实义 bug**：CPL 的 ToolMessage 配对交错（多工具被 OpenAI 兼容 API 400）；
   记忆权值秒级衰减（2 分钟腰斩）改为天级半衰期。
5. **Java HttpClient 必须钉 HTTP/1.1**：默认 HTTP_2 对明文地址发 h2c Upgrade，uvicorn 直接 400。

## 0. 决策记录

| 决策点 | 结论 |
|---|---|
| 架构形态 | **单边车 FastAPI 服务**（`agent-service`），Spring Boot 作 BFF 代理 + 鉴权，前端不直连；允许 Docker 部署 |
| 替换范围 | **全量一次到位**：AI 拆解对话、每日复盘、任务凭证审核全部迁入 agent-service；**moderation 永久留 Java**（fast-path 词表 + 低延迟）；Java 侧保留规则兜底作为降级路径 |
| RAG 存储 | **sqlite-vec**，agent-service 自有向量库，Soulous 写入时推送 `/rag/upsert` |
| 代码位置 | 骨架复制进 **`Soull/agent-service/`** 入库，随主仓版本管理；`Desktop\agent` 原仓不动 |
| 用户隔离 | agent-service 所有持久化（checkpointer / store / 向量库 / DAL）一律以 `user_id` 为命名空间隔离，`user_id` 只信任来自 Spring 的内网请求头（共享 service token） |

> 历史教训：Python 边车（Anima）曾于 2026-06-08 整体下线。本次与 Anima 的区别：**不自写编排 loop**（LangGraph 状态机）、**契约先行**（Spring 侧 API 形态不变，前端零改动）、**每条链路保留 Java 降级路径**（agent 挂了业务不瘫）。

---

## 1. 总体架构

```
浏览器 ──HTTP/SSE──▶ Spring Boot :8080（鉴权/业务/DB/moderation，对外契约不变）
                        │  com.soulous.agent.AgentClient（service token，内网）
                        ▼
                 agent-service :8100（FastAPI + LangGraph）
                   ├── /agent/chat          SSE 流式对话（拆解）
                   ├── /agent/review        任务凭证审核（结构化裁决）
                   ├── /agent/daily-review  每日复盘生成
                   ├── /rag/upsert /rag/search /rag/delete
                   └── /health
                 持久化（data/ 卷）：
                   agent_state.db   LangGraph checkpointer + store（按 user_id 命名空间）
                   agent_data.db    DAL 审计/画像
                   agent_rag.db     sqlite-vec 向量库（每行带 user_id，强制过滤）
```

- 前端**零改动**：`/api/chat` 的 SSE 事件格式、PLAN_JSON / CLARIFY_JSON 落地行为、审核状态机全部保持现状。
- Spring → agent 调用失败时的降级：chat 返回友好错误提示；审核走现有规则兜底（`docs/ai-review-rules.md` 路径保留）；复盘回退现有本地 `LlmService` 路径。降级触发记入日志 + Micrometer 计数。

## 2. agent-service 目录（骨架迁移后）

```
agent-service/
├── app/
│   ├── main.py                 # FastAPI 入口、lifespan 单例（graph/checkpointer/store/rag）
│   ├── api/                    # 路由层：chat.py / review.py / daily_review.py / rag.py
│   ├── core/
│   │   ├── context/            # ContextBuilder、CPL、BudgetManager（重构，见 §4）
│   │   │   └── rag/            # vector_service.py → sqlite-vec 实体化（见 §5）
│   │   ├── guardrail/          # 对接 Soulous moderation fast-path（回调 Spring）
│   │   ├── loop/               # react_loop.py（异步化）、state.py、dal.py
│   │   └── memory/             # MemoryItem / UserProfile / 权值函数（保留）
│   ├── providers/              # llm_provider.py：补 openai 通用项 + mock
│   ├── schemas/                # Pydantic：AgentRequest、PlanDraft、ClarifyQuestion、ReviewVerdict…
│   └── tools/                  # Soulous 业务工具（回调 Spring 内部 API）
├── test/                       # pytest（mock provider 全链路）
├── Dockerfile
├── requirements.txt（或 uv pyproject）
├── .env.example
└── data/                       # 三个 sqlite 库（Docker 卷挂载，gitignore）
```

## 3. 骨架完善清单（服务化 + 修正）

1. **服务层**：FastAPI + lifespan 启动时一次性 compile graph、建立 `AsyncSqliteSaver` / `AsyncSqliteStore` 长连接（现状是每次 `run()` 重开连接重 compile，无法并发）。
2. **流式**：`graph.astream_events` → SSE；token 增量、结构化事件（plan/clarify）、done/error 分事件类型下发，Spring 透传。
3. **多租户**：`thread_id = "{user_id}:{conversation_id}"`；store 命名空间 `("episodes", user_id)`、`("profile", user_id)`；所有读写路径强制带 user_id，不存在跨用户兜底。
4. **CPL 修 bug**：投影时按 `tool_call_id` 把 ToolMessage 紧跟其所属 assistant(tool_calls) 消息交错排列（现状分段平铺，多工具/多轮会被 OpenAI 兼容 API 400 拒绝）。
5. **结构化输出**：`with_structured_output(PlanDraft | ClarifyQuestion | ReviewVerdict)` 替代字符串剥 ```json 围栏；解析失败自动重试一次（对齐 Java 侧 `completeJsonValidated` 的自纠语义）。
6. **结构化输入**：`AgentRequest{user_id, conversation_id, message, attachments[], task_context?, mode}`；附件文本（前端已提取的 md/pdf/txt）作为独立 document 段注入 system_context，不再拼进用户消息。
7. **provider 工厂**：注册表补 `openai`（通用 OpenAI 兼容项，对齐 Soulous 现有 DeepSeek 接入）和 `mock`（确定性回放，供 pytest / 本地联调，对齐 `SOULOUS_LLM_PROVIDER=mock` 工作流）。
8. **guardrail 实体化**：`check_input/check_output` 回调 Spring `/internal/moderation/check`（fast-path 词表，毫秒级）；LLM 级审核仍由 Java moderation 在入口处完成，agent 不重复调用。
9. **工具层**：替换 calculator 演示工具，注册 Soulous 业务工具（均回调 Spring 内部 API，service token 鉴权）：
   - `create_task_draft`（产出任务草案，由 Java 落库为 StudyTask）
   - `query_focus_history`（近 N 天专注时长）
   - `query_timetable`（本周课表/考试）
   - `query_pet_status`（出战宠物等级/心情/streak）
10. **可观测**：结构化日志（user_id/conversation_id/node/latency/tokens）、`/health`（含 provider 可用性）、Prometheus 兼容 `/metrics`（可选）。

## 4. 上下文投影与预算（你的第 3、5 点）

- **分通道预算**：现状只检查 conversation 通道。改为 `system(1500) / conversation(可配) / reasoning+tool(可配)` 各自限额 + 总预算；system_context 内 RAG 段按权值从低到高截断，画像段永不截断。
- **token 计数**：tiktoken cl100k 对 DeepSeek 只是近似，预算阈值全部配置化（`.env`），按 80% 水位触发 summarize。
- **Soulous 画像投影**：system_context 注入用户学习目标、出战宠物（名字/等级/心情）、打卡 streak、当前任务上下文（由 Spring 在请求里带入 `task_context`，agent 不直接查业务库）。
- **relevance 升级**：情景记忆权值中的 relevance 从"字符集交集"换成 embedding 余弦（复用 §5 的 embedding 客户端）。
- **summarize 策略保留**骨架的 RemoveMessage Pop + history_summary 追加机制，摘要 prompt 改写为面向学习对话场景（保留任务清单、已确认的拆解决策、用户偏好）。

## 5. RAG 实体化（你的第 4 点）

- **存储**：sqlite-vec（`agent_rag.db`），表结构 `memory(id, user_id, source_type, source_id, text, embedding BLOB, occurred_at, importance, access_count, last_accessed)`；`(user_id, source_type, source_id)` 唯一约束做 upsert 幂等。
- **语料来源**：Soulous 现有四类记忆（GOAL_MEMORY / SESSION_SUMMARY / COMPLETED_TASK / DAILY_REVIEW）。Java 侧在原 `RagBackfillService` 写入点同步调用 `/rag/upsert` 推送（异步、失败仅记日志不阻塞业务）；提供一次性回灌脚本把存量 `memory_embedding` 导入。
- **embedding**：独立配置（`EMBEDDING_API_KEY/BASE_URL/MODEL`），与 LLM provider 解耦；**删除 md5 伪向量降级**——embedding 不可用时检索返回空集并打 WARN，绝不返回噪音。
- **检索管线**：向量 top-k(20) 召回 → 三维权值重排（融合骨架 `calculate_memory_weight` 的 recency/importance/relevance 与 Soulous 现有 `cos × 0.5^(age/halflife)` 时间衰减）→ 相似度阈值过滤 → 文本去重 → 取 top-3 注入 system_context。
- **维护**：`/rag/delete`（用户注销/任务硬清理用）；access_count/last_accessed 回写保留（命中越多越不易遗忘）。

## 6. Spring 侧改造

新增 `com.soulous.agent` 包：

| 类 | 职责 |
|---|---|
| `AgentClient` | RestClient + SSE 订阅；service token；超时/重试/熔断（简单失败计数即可） |
| `AgentProperties` | `soulous.agent.base-url / token / enabled / timeout` |
| `InternalApiController` | `/internal/**` 内网端点（moderation check、工具回调），service token 过滤器保护 |

改造消费方：

- `ChatService`：LLM 调用链路换成 `AgentClient.chatStream(...)` 透传 SSE；滚动摘要、PLAN/CLARIFY 解析逻辑**下沉到 agent**，Java 只负责落库（消息持久化、PLAN_JSON → StudyTask）与对外契约。
- `AiService`（任务审核）：组装 `ReviewRequest` 调 `/agent/review`，拿回 `ReviewVerdict` 走现有状态机；agent 不可用 → 现有规则兜底原样保留。
- `DailyReviewService`：调 `/agent/daily-review`；失败回退现有本地路径。
- `RagBackfillService` / 各记忆写入点：追加 `/rag/upsert` 推送。
- `aisession` 包维持下线状态不动；`moderation` 不动；`ai.LlmService` **保留**（mock 联调、复盘降级、健康检查仍用），仅不再是主链路。

## 7. 部署（Docker）

- `agent-service/Dockerfile`（python:3.13-slim + uv），`data/` 挂卷。
- 根目录 `docker-compose.agent.yml`：单容器 agent-service，端口 8100 仅绑内网/localhost。
- VPS：systemd 或 compose 二选一；Spring 配 `soulous.agent.base-url=http://127.0.0.1:8100`。
- 本地联调：`SOULOUS_LLM_PROVIDER=mock` + agent `ACTIVE_PROVIDER=mock`，无 key 全链路可跑。

## 8. 实施批次与验收

| 批次 | 内容 | 验收 |
|---|---|---|
| B1 | 骨架入库 `agent-service/` + FastAPI 服务化 + 异步单例 + provider(openai/mock) + CPL 修复 + 结构化 IO | pytest：mock provider 下多轮对话/多工具/预算裁剪全绿 |
| B2 | RAG 实体化（sqlite-vec + upsert/search + 重排管线 + 存量回灌脚本） | pytest：隔离性（用户 A 检索不到 B）、衰减重排正确性 |
| B3 | Spring `AgentClient` + chat/review/daily-review 切换 + 降级路径 + `/internal` 端点 | `mvn test` 全绿；mock 下前端聊天/审核/复盘手测通 |
| B4 | 业务工具注册 + guardrail 对接 + 可观测 | 工具调用 e2e（mock LLM 脚本化 tool_calls） |
| B5 | Docker + 文档同步（architecture.md / api.md / DEPLOY.md / CLAUDE.md 命令区） | compose 起服务，health 通过 |

全程前端零改动；`npx tsc --noEmit && npm test` 仅作回归确认。

## 9. 风险

- **延迟**：多一跳内网 HTTP，流式场景可忽略；审核场景同步调用，超时设 30s 与现状一致。
- **sqlite-vec 并发**：WAL 模式 + 单写者（FastAPI 单进程多协程），用户量级下足够；将来切 pgvector 只动 vector_service 一层。
- **双库一致性**：RAG 推送是 best-effort，丢失只影响召回质量不影响业务正确性；回灌脚本可随时重放。
- **Anima 复辙**：每条链路保留 Java 降级 + 对外契约冻结，agent 整体摘除时业务可立即回退现状。
