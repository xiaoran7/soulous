# Soulous — Claude 工作守则

全栈学习打卡宠物成长 App：Spring Boot 3 + Java 21（backend/）+ React 19 + Vite + TS（frontend/）
+ agent-service/（Python 3.13 + FastAPI + LangGraph 认知边车：聊天/审核/复盘三条 AI 链路 + sqlite-vec RAG，
Spring 经 `com.soulous.agent.AgentClient` 调用，agent 故障自动回退本地 LlmService/规则）。
UI 是「Luminous Ethereal」玻璃拟态：全局场景照片背景 + 顶部悬浮玻璃导航，登录后默认落在自习室主页。

## 常用命令

```bash
# 后端（本地联调：关验证码/限流，LLM 用 mock）
SOULOUS_CAPTCHA_ENABLED=false SOULOUS_RATE_LIMIT_ENABLED=false SOULOUS_LLM_PROVIDER=mock \
  mvn -q -f backend/pom.xml spring-boot:run   # Bash 工具需 dangerouslyDisableSandbox，否则 JVM 起不来
# 要走 agent 链路时追加：SOULOUS_AGENT_ENABLED=true SOULOUS_AGENT_TOKEN=devtoken

# agent-service（mock provider 零 key 全链路；token 与后端 SOULOUS_AGENT_TOKEN 一致）
cd agent-service && ACTIVE_PROVIDER=mock AGENT_SERVICE_TOKEN=devtoken \
  .venv/Scripts/python -m uvicorn app.main:app --port 8100
cd agent-service && .venv/Scripts/python -m pytest -q   # 26 项，零网络

# 前端
cd frontend && npm run dev      # 用户常驻一个 vite 在 5173，别杀它
cd frontend && npx tsc --noEmit && npm test && npm run build
cd backend && mvn test
```

## 硬性红线（违反 = 复现已修过的事故）

- **`.pet-sprite` 及任何包含逐帧精灵的容器禁加 CSS `filter`**（如 drop-shadow）：精灵每 ~100ms 切帧，filter 强制每帧重栅格化，全站毛玻璃跟着闪。
- **backdrop-filter 元素禁 `will-change`、禁 `background-attachment: fixed`、禁无限位移动画**：三者都是 Windows Chrome 毛玻璃闪烁的实证元凶。
- **玻璃模糊一律放在零内容空伪元素上**（`::before`/`::after`：`inset:0; z-index:-1; border-radius:inherit; pointer-events:none`），元素本体只留底色/描边。backdrop-filter 挂在含动态内容（逐帧精灵、计时跳字、流式文本、输入光标、进度动画）的元素本体上，内容任何重绘都强制整块玻璃面重栅格化——这是 2026-06 多轮修复后仍闪的根因。新玻璃组件照抄 `.glass-card`/`.panel` 的伪元素模式；宿主需有 stacking context（`transform: translateZ(0)` 或 `isolation: isolate`），且当心后置 `position: relative` 覆盖早先定义的 `position: absolute`（用 `:where()` 兜底）。
- **玻璃白雾不透明度是可读性底线**：`--glass-fill` ≥ 0.5、strong ≥ 0.7。深色场景照片透过更透的玻璃会吃掉暖墨文字，不要再调透。
- **PetSprite 必须保持纯 CSS 动画**（合成器驱动、零 JS 重渲染），不要改回 setTimeout/setState 逐帧。
- 布局选择器必须写 `.app-shell > main`，裸 `main` 会误伤 ChatPage 内嵌的 `<main class="chat-content">`。
- 任务 DELETE 是软归档（写 archivedAt），不要做硬删/级联。
- 不要给散点瓷片同时挂 `--tilt` 旋转和位移动画（transform 互相覆盖）。

## 环境与调试坑

- styles.css 是 **CRLF** 行尾，node 脚本批量替换前先探测行尾。
- **preview 窗口 `visibilityState=hidden`、rAF 零帧**：CSS 动画/过渡停在第 0 帧、`preview_screenshot` 必超时——状态判断一律用 `preview_eval` 查 DOM/计算样式；动画类改动只能靠用户真机确认。
- 后端 CORS 白名单只认 5173；vite.config.ts 支持 PORT 变量并把代理 Origin 重写为 5173，preview_start 跑副本即可。
- 登录 SVG 验证码可 atob 解码后从 `<text>` 标签直接读字符。
- 本地测试账号：clauder / stitch（H2 文件库 backend/data/）。

## 深入文档

| 主题 | 文档 |
|---|---|
| agent-service 架构 / API / 骨架改造记录 | agent-service/README.md |
| agent 集成决策与实施偏差 | docs/agent-service-integration-plan.md |
| 交接总览 / 关键文件 / 约定 | docs/handoff-to-claude.md |
| 架构 / 模块表 / 数据流 | docs/architecture.md |
| 代码导览（功能→包→页面→表） | docs/CODE_WALKTHROUGH.md |
| API 参考 | docs/api.md |
| 数据库 / Flyway | docs/database.md |
| 生产部署 / VPS | DEPLOY.md、docs/deployment.md |
| 用户视角功能说明 | docs/user-guide.md |
