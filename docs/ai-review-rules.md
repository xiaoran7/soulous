# AI 审核规则

提交凭证后的审核走**两级链**（`AiReviewProcessor.runReview`，异步、off-request-thread）：

```
提交 → 内容安全审核(moderation，保留不变)
     → ① AiService LLM 审核     ← 首选
        LLM 不可用 ↓
     → ② 规则兜底评分           ← 保底
```

任一级产出统一的 `AiReview{result, relevanceScore, completenessScore, qualityScore, recommendedExp, reason, suggestion, needManual}`，由 `TaskService.applyAiReview` 落地（PASS→任务完成+发经验；NEED_MORE→补充；REJECT/MANUAL→驳回/转人工）。

> 历史：曾有一层「宠物审核」把提交委托给独立的 Anima agent 服务（飞雪人格 + 记忆）做首选裁决，2026-06-08 连同陪伴模块整体下线，审核回归纯本地 `AiService`。

## ① / ② 本地审核

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
