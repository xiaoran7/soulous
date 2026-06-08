# AI 审核规则

提交凭证后的审核走**三级链**（`AiReviewProcessor.runReview`，异步、off-request-thread）：

```
提交 → 内容安全审核(moderation，保留不变)
     → ① 宠物审核(Anima/飞雪)   ← 首选，带记忆
        失败/Anima 关闭 ↓
     → ② AiService LLM 审核     ← 回退
        LLM 不可用 ↓
     → ③ 规则兜底评分           ← 最后保底
```

任一级产出统一的 `AiReview{result, relevanceScore, completenessScore, qualityScore, recommendedExp, reason, suggestion, needManual}`，由 `TaskService.applyAiReview` 落地（PASS→任务完成+发经验；NEED_MORE→补充；REJECT/MANUAL→驳回/转人工）。

## ① 宠物审核（Anima，首选）

把提交详情推给独立的 **Anima** 服务（`POST /v1/review`），由宠物（飞雪人格）**结合对该用户的记忆**评判：

- 产出结构化裁决（result/三维分/经验/理由/建议）**和一段飞雪口吻的聊天回复**。
- 这次「提交摘要 + 飞雪反馈」写进该用户的宠物会话（`pet-{userId}`）→ **进记忆、聊天框可见**（`GET /api/companion/history`）。
- Soulous 侧映射：`result` 字符串 → `AiReviewResult`，`MANUAL`/未知 → `NEED_MORE`（拿不准时让用户补充，比直接驳回友好）。
- 关闭/不可用/解析失败 → 返回 null，回退到 ②。开关见 `soulous.companion.*`。

## ② / ③ 本地审核（回退）

`AiService.review` 先试 LLM（`completeJson`，返回固定 JSON schema），不可用再走规则兜底。

**输入**：任务标题/描述/课程、文字凭证、学习时长、是否有截图、代码片段、链接。
（注意：审核**只看文本**，截图只判断"有/无"，不做图像识别——所以整套审核都能跑在纯文本模型上。）

**规则兜底评分维度**：
- 相关性：凭证文本是否包含任务关键词。
- 完整度：文本长度 + 学习时长。
- 质量分：长说明 / 时长 / 截图 / 代码片段等增强。

```text
score = (relevance + completeness + quality) / 3
```

**规则结果**：文本 < 6 字符 → `REJECT`；总分 ≥ 70 → `PASS`；≥ 45 → `NEED_MORE`；否则 `REJECT`。
通过时 `recommendedExp = task.baseExp * score / 100`，写 `exp_log` 并更新宠物经验。
