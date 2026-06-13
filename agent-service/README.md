# Soulous Agent Service

Soulous 的 AI 认知边车：基于「通用骨架 agent」（LangGraph 状态机 + 多通道消息隔离 + CPL 上下文投影）适配而来的 FastAPI 服务，承接 **AI 拆解对话 / 任务凭证审核 / 每日复盘** 三条 LLM 链路与 **实体化 RAG 检索**。

Spring Boot（`com.soulous.agent.AgentClient`）作为唯一调用方；前端不直连。agent 不可用时 Java 侧自动回退本地 `LlmService` / 规则路径，业务不受影响。

## 架构速览

```
app/
├── main.py                  # FastAPI 入口：lifespan 单例 + X-Service-Token 鉴权 + SSE
├── config.py                # 环境变量配置（.env）
├── schemas/models.py        # 对外契约（Pydantic，线格式 camelCase 对齐 Jackson）
├── core/
│   ├── loop/
│   │   ├── react_loop.py    # LangGraph 状态图：context→(summarize)→agent⇄action→memory
│   │   ├── state.py         # 多通道 AgentState（conversation/reasoning/tool 物理分离）
│   │   ├── envelopes.py     # PLAN_JSON/CLARIFY_JSON 解析 + Pydantic 校验 + 修复重抽
│   │   └── dal.py           # 审计/画像 sqlite（agent_data.db）
│   ├── context/
│   │   ├── context_builder.py   # CPL（tool_call 配对投影）+ 分通道预算 + 系统 prompt 组装
│   │   └── rag/vector_service.py # sqlite-vec 向量库（agent_rag.db），三维权值重排
│   ├── guardrail/guardrail.py   # 回调 Spring /internal/moderation/check（fail-open）
│   └── memory/base.py           # MemoryItem / UserProfile / 记忆权值（天级半衰期）
├── providers/               # LLM 工厂（openai/deepseek/mimo/mock）
├── services/ai_tasks.py     # 单发任务：审核 + 复盘（JSON 自纠重试）
└── tools/soulous_tools.py   # 业务工具：专注历史/课表/宠物状态（回调 Spring /internal）
```

### 相对骨架原版的关键改造

| 点 | 原版 | 现状 |
|---|---|---|
| 服务形态 | 同步 `run()`，每次重开连接重 compile | FastAPI + lifespan 单例 + AsyncSqliteSaver 长连接，多用户并发 |
| 多租户 | 无 | `thread_id = userId:conversationId`；向量库/DAL/工具全按 user_id 隔离 |
| CPL | reasoning/tool 分段平铺（多工具场景被 OpenAI 兼容 API 400） | ToolMessage 按 `tool_call_id` 紧跟所属 assistant 消息交错 |
| RAG | 进程内 list + md5 伪向量降级（噪音） | sqlite-vec 持久化；embedding 不可用返回空集；top-20 召回 → relevance/recency/importance 重排 → 去重 top-k |
| 情景记忆 | SqliteStore 命名空间 + 字符集交集 relevance | 统一入向量库（EPISODE 类型），relevance 即 embedding 余弦；SqliteStore 已退役，画像归 DAL |
| 结构化输出 | 字符串剥 ```json | Pydantic 校验 + 一次自纠重试 + 计划修复重抽 |
| 记忆衰减 | 秒级指数衰减（2 分钟腰斩，bug） | 天级半衰期 `0.5^(ageDays/halfLife)`，与 Soulous RAG 同构 |
| 工具 | calculator 演示 | 业务工具回调 Spring `/internal`（service token，user_id 由上下文注入不可伪造） |

## API

所有端点（除 `/health`）要求请求头 `X-Service-Token` 匹配 `AGENT_SERVICE_TOKEN`。

| 端点 | 说明 |
|---|---|
| `POST /agent/chat` | 拆解对话（非流式）→ `{reply, plan?, clarify?}` |
| `POST /agent/chat/stream` | SSE：`token`* + `status`（工具调用 `{stage: tool/tool_done, name}`）→ `done`（含结构化 plan/clarify）/ `error`；记忆持久化（蒸馏/提炼）已后台化，不阻塞 `done` |
| `POST /agent/review` | 任务凭证审核 → ReviewVerdict（失败 502，Java 走规则兜底） |
| `POST /agent/daily-review` | 每日复盘 → DailyReviewResult |
| `POST /agent/daily-review/stream` | SSE：叙述 token（REVIEW_JSON 信封已拦下）→ `done` |
| `POST /rag/upsert` / `search` / `delete` | 向量库维护与检索（幂等 upsert，按 user 隔离） |
| `GET /health` | 存活 + LLM/embedding 可用性 |

## 运行

```powershell
# 1. 安装
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt

# 2. 配置（mock = 零 key 全链路联调，对齐后端 SOULOUS_LLM_PROVIDER=mock）
copy .env.example .env

# 3. 启动
.\.venv\Scripts\python -m uvicorn app.main:app --port 8100

# 4. 测试（26 项，零网络）
.\.venv\Scripts\python -m pytest -q
```

Spring 侧开启对接：`SOULOUS_AGENT_ENABLED=true SOULOUS_AGENT_TOKEN=<与 AGENT_SERVICE_TOKEN 一致>`；
存量 RAG 语料一次性回灌：临时加 `SOULOUS_AGENT_BACKFILL=true` 启动一次后端。

Docker 部署见仓库根 `docker-compose.agent.yml`。

## 数据文件（`data/`，gitignore）

| 文件 | 角色 |
|---|---|
| `agent_state.db` | LangGraph checkpointer：每个 thread（userId:conversationId）的多通道状态快照 |
| `agent_data.db` | DAL：交互审计日志（含思维链蒸馏归档）+ 用户画像镜像 |
| `agent_rag.db` | sqlite-vec 向量库：EPISODE 情景记忆 + Soulous 四类业务记忆 |
