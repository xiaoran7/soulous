"""
上下文构建层：预算管理（Budget Manager）+ 投影层（CPL）+ 系统 Prompt 组装器。

相对骨架原版的关键改造：
  1. CPL 修复：ToolMessage 必须紧跟其所属的 assistant(tool_calls) 消息按 tool_call_id 配对交错，
     原版分段平铺在多工具/多轮场景会被 OpenAI 兼容 API 以 400 拒绝；
  2. 分通道预算：system / conversation / scratch(reasoning+tool) 各自限额，阈值配置化；
  3. Soulous 化系统 Prompt：学习助手人设 + PLAN_JSON/CLARIFY_JSON 信封协议（与前端契约一致），
     注入历史摘要、用户画像、情景记忆与 RAG 召回、附件文本；
  4. 情景记忆 relevance 由 embedding 余弦驱动（统一走 VectorService，EPISODE 类型），
     淘汰原版的字符集交集近似。
"""
from typing import Any, Dict, List, Optional

import tiktoken
from langchain_core.messages import BaseMessage, SystemMessage, ToolMessage

from app.core.context.rag.vector_service import VectorService
from app.core.memory.base import UserProfile

SOULOUS_RAG_TYPES = ["GOAL_MEMORY", "SESSION_SUMMARY", "COMPLETED_TASK", "DAILY_REVIEW"]
EPISODE_TYPE = "EPISODE"

RAG_LABELS = {
    "GOAL_MEMORY": "过往目标记忆",
    "SESSION_SUMMARY": "过往拆解对话",
    "COMPLETED_TASK": "已完成任务",
    "DAILY_REVIEW": "过往每日复盘",
    "EPISODE": "情景记忆",
}

CHAT_PERSONA = """\
# 角色定位
你是 Soulous 的 AI 学习助手，帮助用户解答学习问题、梳理思路，并在合适时把目标拆解成可执行的学习任务。
你可以调用提供的工具查询用户的专注历史、课表和宠物状态来给出更贴合的建议；查询类问题先用工具拿到事实再回答。

# 行为规则
1. 平时用简洁、有条理的中文正常对话答疑，不要无故强行拆任务。
2. 当用户明确想「把它拆成任务 / 给我一个学习计划 / 制定计划」，或当前诉求确实适合落地为一组可执行学习任务时，在回复末尾输出 <PLAN_JSON> 信封。
3. 计划必须包含一个 category（这组任务的「大类」归类名，必填，≤12 字，如「数据结构」「考研数学」）和 3–7 个任务；每个任务字段：title（≤40字）、description、estimatedMinutes（15–90）、difficulty（EASY/NORMAL/HARD）、taskType（STUDY/CODING/NOTE/MEMORY/REVIEW/SIMPLE）。由易到难、循序渐进。你必须主动起好这个大类名，绝不要留空。
4. 信息不足以拆解、且关键空白会实质影响任务编排时，用 <CLARIFY_JSON> 信封提问（≤2 题、每题 2–4 个可直接点选的完整选项），不要用纯文字问。一条回复里 CLARIFY_JSON 与 PLAN_JSON 二选一。
5. 你"问问题"必须通过 <CLARIFY_JSON>...</CLARIFY_JSON>、"给计划"必须通过 <PLAN_JSON>...</PLAN_JSON>，且必须真的输出对应标签包裹的合法 JSON。绝对禁止只在文字里声称「已生成计划草案」却不输出信封。

<CLARIFY_JSON> 格式（每题 2–4 个 options，单选 multiSelect:false，多选 true）：
<CLARIFY_JSON>
{"questions":[{"id":"tool","question":"你打算用什么工具或语言？","multiSelect":false,"options":[{"label":"Python","hint":"适合数据/AI"},{"label":"JavaScript"}]}]}
</CLARIFY_JSON>

<PLAN_JSON> 格式（category 必填）：
<PLAN_JSON>
{"category":"数据结构","tasks":[{"title":"...","description":"...","estimatedMinutes":30,"difficulty":"NORMAL","taskType":"STUDY"}]}
</PLAN_JSON>
"""


class ContextBudgetManager:
    """
    分通道上下文预算管理器。
    各通道独立限额（防注意力迷失），conversation 超限触发 summarize 节点瘦身。
    """
    def __init__(self, encoding, system_limit: int = 2000,
                 conversation_limit: int = 2400, scratch_limit: int = 2000):
        self.encoding = encoding
        self.system_limit = system_limit
        self.conversation_limit = conversation_limit
        self.scratch_limit = scratch_limit

    def count_tokens(self, text: str) -> int:
        if not text:
            return 0
        return len(self.encoding.encode(str(text)))

    def count_messages_tokens(self, messages: List[BaseMessage]) -> int:
        return sum(self.count_tokens(m.content) + 4 for m in messages)

    def check_budget(self, state: Dict[str, Any]) -> bool:
        """conversation 通道超过限额 80% 水位即标记超限（提前瘦身，留出回答余量）。"""
        conversation_tokens = self.count_messages_tokens(state.get("conversation_messages", []))
        return conversation_tokens > self.conversation_limit * 0.8

    def clip_to_limit(self, text: str, limit: int) -> str:
        """按 token 限额截断文本（用于 RAG 段落裁剪）。"""
        tokens = self.encoding.encode(text)
        if len(tokens) <= limit:
            return text
        return self.encoding.decode(tokens[:limit])


class ContextProjectionLayer:
    """
    上下文投影层 (CPL)：将多通道状态平铺为 LLM 标准消息序列。
    关键约束：每条带 tool_calls 的 assistant 消息后必须立即跟上其全部 ToolMessage
    （按 tool_call_id 配对），否则 OpenAI 兼容 API 直接 400。
    """
    @staticmethod
    def project(state: Dict[str, Any]) -> List[BaseMessage]:
        payload: List[BaseMessage] = []

        if state.get("system_context"):
            payload.append(state["system_context"])

        if state.get("conversation_messages"):
            payload.extend(state["conversation_messages"])

        # 工具结果按 tool_call_id 建立索引
        tool_by_id: Dict[str, ToolMessage] = {}
        for tm in state.get("tool_messages", []) or []:
            if isinstance(tm, ToolMessage) and tm.tool_call_id:
                tool_by_id[tm.tool_call_id] = tm

        # reasoning 流内逐条交错：assistant(tool_calls) → 对应 ToolMessages
        for msg in state.get("reasoning_messages", []) or []:
            payload.append(msg)
            for tc in getattr(msg, "tool_calls", None) or []:
                tm = tool_by_id.get(tc.get("id"))
                if tm is not None:
                    payload.append(tm)

        return payload


class ContextBuilder:
    """
    系统 Prompt 组装器：人设 + 历史摘要 + 画像 + 情景记忆 + RAG 召回 + 附件，
    全部受 system 通道预算约束（RAG/附件段按权值与限额截断）。
    """
    def __init__(self, vector_service: Optional[VectorService] = None,
                 system_limit: int = 2000, conversation_limit: int = 2400,
                 scratch_limit: int = 2000, encoding_name: str = "cl100k_base"):
        try:
            self.encoding = tiktoken.get_encoding(encoding_name)
        except Exception:
            self.encoding = tiktoken.get_encoding("cl100k_base")
        self.vector_service = vector_service
        self.budget_manager = ContextBudgetManager(
            self.encoding, system_limit, conversation_limit, scratch_limit)

    def build_system_prompt(self, runtime_state: Any,
                            user_profile: Optional[UserProfile] = None,
                            history_summary: str = "") -> SystemMessage:
        lines = [CHAT_PERSONA, ""]

        # 1. 前序对话历史摘要（Pop 裁剪后的提炼）
        if history_summary:
            lines.append("## 前序对话历史摘要")
            lines.append(f"> {history_summary}")
            lines.append("")

        # 2. 用户画像（Soulous 业务投影 + 长期 Semantic 记忆），永不截断
        profile_lines = []
        ctx: Dict[str, Any] = getattr(runtime_state, "soulous_context", None) or {}
        if ctx.get("goal"):
            profile_lines.append(f"- 学习目标：{ctx['goal']}")
        if ctx.get("pet_name"):
            pet = f"- 出战宠物：{ctx['pet_name']}"
            if ctx.get("pet_level"):
                pet += f"（Lv.{ctx['pet_level']}）"
            profile_lines.append(pet)
        if ctx.get("streak"):
            profile_lines.append(f"- 连续打卡：{ctx['streak']} 天")
        if ctx.get("task_context"):
            profile_lines.append(f"- 当前任务上下文：{ctx['task_context']}")
        if user_profile:
            if user_profile.background:
                profile_lines.append(f"- 背景：{user_profile.background}")
            if user_profile.preferences:
                profile_lines.append(f"- 偏好：{user_profile.preferences}")
        if profile_lines:
            lines.append("## 用户画像")
            lines.extend(profile_lines)
            lines.append("")

        # 3. 情景记忆 + RAG 知识召回（embedding 余弦驱动，按权值排序，整段受限额截断）
        query = getattr(runtime_state, "current_query", "") or ""
        recall_lines = self._build_recall_block(getattr(runtime_state, "user_id", ""), query)
        if recall_lines:
            recall_text = "\n".join(recall_lines)
            recall_text = self.budget_manager.clip_to_limit(
                recall_text, max(200, self.budget_manager.system_limit // 2))
            lines.append(recall_text)
            lines.append("")

        # 4. 附件文本（独立 document 段注入，不拼进用户消息；占剩余预算）
        attachments = getattr(runtime_state, "attachments", None) or []
        if attachments:
            lines.append("## 用户上传的附件资料")
            used = self.budget_manager.count_tokens("\n".join(lines))
            remaining = max(300, self.budget_manager.system_limit - used)
            per_doc = max(150, remaining // len(attachments))
            for att in attachments:
                name = att.get("name", "附件") if isinstance(att, dict) else getattr(att, "name", "附件")
                text = att.get("text", "") if isinstance(att, dict) else getattr(att, "text", "")
                clipped = self.budget_manager.clip_to_limit(text, per_doc)
                lines.append(f'<ATTACHMENT name="{name}">\n{clipped}\n</ATTACHMENT>')
            lines.append("")

        return SystemMessage(content="\n".join(lines))

    def _build_recall_block(self, user_id: str, query: str) -> List[str]:
        """统一从 VectorService 召回情景记忆（EPISODE）与 Soulous 四类业务记忆。"""
        if not self.vector_service or not self.vector_service.available or not query or not user_id:
            return []
        lines: List[str] = []
        try:
            episodes = self.vector_service.search(user_id, query, k=3, source_types=[EPISODE_TYPE])
            if episodes:
                lines.append("## 召回的情景记忆（按权值排序）")
                for ep in episodes:
                    lines.append(f"- [权值 {ep['weight']:.2f}] {ep['text']}")
            knowledge = self.vector_service.search(user_id, query, k=3, source_types=SOULOUS_RAG_TYPES)
            if knowledge:
                lines.append("## 用户历史相关记忆（RAG 召回）")
                for idx, doc in enumerate(knowledge):
                    label = RAG_LABELS.get(doc["source_type"], doc["source_type"])
                    lines.append(f"- [{idx + 1}] {label}（相似度 {doc['similarity']:.2f}）：{doc['text']}")
                lines.append("（请把以上历史作为参考语境，但以用户当前发言为准。）")
        except Exception:
            return lines
        return lines
