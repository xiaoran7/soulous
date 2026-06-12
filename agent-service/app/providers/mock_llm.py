"""
确定性 Mock LLM：零 API key 跑通全链路（pytest / 本地联调），
对齐 Soulous 后端 SOULOUS_LLM_PROVIDER=mock 的工作流。

行为按输入内容路由：
  - 审核类 prompt（系统词含「审核」）→ 返回 ReviewVerdict JSON；
  - 复盘类 prompt（系统词含「复盘」）→ 返回 DailyReviewResult JSON；
  - 摘要/蒸馏类 prompt → 返回一句压缩摘要；
  - 用户消息含「拆解/计划/plan」→ 返回带 <PLAN_JSON> 信封的拆解回复；
  - 其余 → 通用答疑回复。
"""
import re
from typing import Any, Iterator, List, Optional, Sequence

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, AIMessageChunk, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatGenerationChunk, ChatResult

PLAN_REPLY = (
    "好的，我把这个目标拆成了循序渐进的几个任务，确认后即可落地：\n"
    "<PLAN_JSON>\n"
    '{"category":"模拟学习","tasks":['
    '{"title":"明确学习目标","description":"写下要解决的 2-3 个具体问题。","estimatedMinutes":15,"difficulty":"EASY","taskType":"STUDY","baseExp":10},'
    '{"title":"学习核心概念","description":"阅读资料并整理关键概念。","estimatedMinutes":30,"difficulty":"NORMAL","taskType":"NOTE","baseExp":20},'
    '{"title":"完成一次练习","description":"围绕目标完成一道题或一段代码。","estimatedMinutes":40,"difficulty":"NORMAL","taskType":"CODING","baseExp":25}'
    "]}\n"
    "</PLAN_JSON>"
)

REVIEW_REPLY = (
    '{"result":"PASS","relevanceScore":82,"completenessScore":78,"qualityScore":80,"score":80,'
    '"reason":"凭证与任务相关且内容具体。","suggestion":"下次可以补充截图进一步提高可信度。",'
    '"recommendedExp":16,"needManual":false}'
)

DAILY_REVIEW_REPLY = (
    "今天的节奏不错，学习闭环已经形成，继续保持。\n"
    "<REVIEW_JSON>"
    '{"title":"今天的学习闭环已经形成","summary":"完成了既定任务并记录了学习时长，整体推进顺利。",'
    '"highlights":["完成了今日核心任务","保持了连续学习记录"],"risks":["注意休息节奏"],'
    '"tomorrowSuggestions":["继续推进未完成任务","保持凭证具体可验证"],"petMessage":"宠物为你今天的坚持感到开心！"}'
    "</REVIEW_JSON>"
)

MEMORY_ANALYSIS_REPLY = (
    '{"has_episodic":true,"episodic_content":"用户完成了一轮学习目标拆解对话。","importance":6.0,'
    '"has_semantic":false,"semantic_background":"","semantic_preferences":{}}'
)


class MockChatModel(BaseChatModel):
    """脚本化聊天模型：同步/流式皆为确定性输出，bind_tools 直接透传自身。"""

    @property
    def _llm_type(self) -> str:
        return "soulous-mock"

    def bind_tools(self, tools: Sequence[Any], **kwargs: Any) -> "MockChatModel":
        return self

    def _route(self, messages: List[BaseMessage]) -> str:
        system_text = " ".join(m.content for m in messages if m.type == "system" if isinstance(m.content, str))
        human_text = " ".join(m.content for m in messages if m.type == "human" if isinstance(m.content, str))
        # 内部调用（记忆分析/摘要/蒸馏）优先匹配，避免其 prompt 中夹带的用户原文误触发计划分支
        if "情景记忆" in human_text or "稳定事实" in human_text:
            return MEMORY_ANALYSIS_REPLY
        if "总结器" in human_text or "蒸馏" in human_text or "压缩摘要" in system_text:
            return "（模拟摘要）用户与助手围绕学习安排进行了讨论，已确认的关键结论被保留。"
        if "审核" in system_text:
            return REVIEW_REPLY
        if "复盘" in system_text:
            return DAILY_REVIEW_REPLY
        if re.search(r"拆解|计划|plan", human_text, re.IGNORECASE):
            return PLAN_REPLY
        return "（模拟回复）我理解你的问题，这是一个用于联调的确定性回答。"

    def _generate(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Optional[CallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> ChatResult:
        reply = self._route(messages)
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content=reply))])

    def _stream(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Optional[CallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> Iterator[ChatGenerationChunk]:
        reply = self._route(messages)
        # 按 12 字符切片模拟 token 流
        for i in range(0, len(reply), 12):
            yield ChatGenerationChunk(message=AIMessageChunk(content=reply[i:i + 12]))
