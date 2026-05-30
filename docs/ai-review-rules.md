# AI 审核规则

MVP 中 `AiService` 是规则模拟实现，不依赖真实模型 API。

## 审核输入

- 任务标题、描述、课程
- 用户文字凭证
- 学习时长
- 截图 URL
- 代码片段
- 链接

## 评分维度

- 相关性：凭证文本是否包含任务标题、描述或课程中的关键词。
- 完整度：文本长度和学习时长是否足以体现学习过程。
- 质量分：是否有较长说明、学习时长、截图、代码片段等增强凭证。

总分：

```text
score = (relevance + completeness + quality) / 3
```

## 结果规则

- 文本少于 6 个字符：`REJECT`
- 总分 >= 70：`PASS`
- 总分 >= 45：`NEED_MORE`
- 其他：`REJECT`

通过时：

```text
recommendedExp = task.baseExp * score / 100
```

系统会写入 `exp_log` 并更新宠物经验。

## 真实模型接入点

后续只需要替换 `AiService.review` 和 `AiService.decompose`：

- `review` 调用真实模型，返回固定 JSON。
- `decompose` 调用真实模型，将学习目标拆成任务数组。
- Controller 和业务状态流转不需要改动。
