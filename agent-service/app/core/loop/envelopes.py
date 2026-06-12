"""
结构化信封解析与自纠修复。

PLAN_JSON / CLARIFY_JSON 信封与 Soulous 前端契约一致；
解析结果一律过 Pydantic 校验（替代裸正则剥 JSON），
「声称给了计划却没给信封」时触发一次聚焦的修复重抽（对齐 Java 侧 repairPlanEnvelope 语义）。
"""
import json
import logging
import re
from typing import Optional

from langchain_core.messages import HumanMessage, SystemMessage

from app.schemas.models import ClarifyDraft, PlanDraft

logger = logging.getLogger("Envelopes")

PLAN_REPAIR_SYSTEM = """\
你是一个把自然语言学习计划转写成结构化 JSON 的助手。只输出一个 JSON 对象，禁止任何解释、Markdown 围栏或多余文字。
Schema：
{"category":"这组任务的大类归类名（必填，≤12字）","tasks":[{"title":"≤40字","description":"...","estimatedMinutes":30,"difficulty":"EASY|NORMAL|HARD","taskType":"STUDY|CODING|NOTE|MEMORY|REVIEW|SIMPLE"}]}
要求：
- 忠实还原那段话里描述的任务，3–7 个，不要凭空新增或删减成完全不同的计划。
- category 必须自己起好，绝不能留空。
- 字段缺失时按合理默认补全（estimatedMinutes 取 15–90，difficulty 默认 NORMAL，taskType 默认 STUDY）。
"""


def extract_tag_json(text: str, tag: str) -> Optional[dict]:
    """提取 <TAG>...</TAG> 包裹的 JSON；缺失或非法返回 None。"""
    if not text:
        return None
    open_tag, close_tag = f"<{tag}>", f"</{tag}>"
    start = text.find(open_tag)
    end = text.find(close_tag)
    if start < 0 or end <= start:
        return None
    inner = text[start + len(open_tag):end].strip()
    if inner.startswith("```"):
        inner = re.sub(r"^```(json)?|```$", "", inner, flags=re.MULTILINE).strip()
    try:
        return json.loads(inner)
    except Exception as e:
        logger.warning("[ENVELOPE] %s 解析失败: %s", tag, e)
        return None


def parse_plan(text: str) -> Optional[PlanDraft]:
    data = extract_tag_json(text, "PLAN_JSON")
    if data is None:
        return None
    try:
        plan = PlanDraft.model_validate(data)
        return plan if plan.usable() else None
    except Exception as e:
        logger.warning("[ENVELOPE] PLAN_JSON 校验失败: %s", e)
        return None


def parse_clarify(text: str) -> Optional[ClarifyDraft]:
    data = extract_tag_json(text, "CLARIFY_JSON")
    if data is None:
        return None
    try:
        clarify = ClarifyDraft.model_validate(data)
        return clarify if clarify.usable() else None
    except Exception as e:
        logger.warning("[ENVELOPE] CLARIFY_JSON 校验失败: %s", e)
        return None


def looks_like_plan_claim(reply: str) -> bool:
    """启发式：回复声称给了计划但没输出信封时返回 True，触发修复重抽。"""
    if not reply:
        return False
    mentions_plan = any(w in reply for w in ("计划", "任务", "plan", "PLAN"))
    if not mentions_plan:
        return False
    claims = ("草案", "已生成", "如下", "↓", "PLAN_JSON", "为你定制",
              "已为你", "制定了", "帮你拆", "拆成", "拆解为", "个任务")
    return any(w in reply for w in claims)


def strip_json_fence(raw: str) -> str:
    raw = raw.strip()
    if raw.startswith("```"):
        raw = re.sub(r"^```(json)?\s*|\s*```$", "", raw).strip()
    return raw


async def repair_plan(llm, prose: str) -> Optional[PlanDraft]:
    """二次调用 LLM 把自然语言计划转成严格 JSON，过 Pydantic 校验，失败放弃修复。"""
    user = ("你刚才对用户说的下面这段话里描述了一份学习计划，但你忘了输出机器可读的计划数据。"
            "请把这份计划严格转换成约定的 JSON。\n\n你刚才的话：\n" + (prose or ""))
    try:
        resp = await llm.ainvoke([SystemMessage(content=PLAN_REPAIR_SYSTEM), HumanMessage(content=user)])
        data = json.loads(strip_json_fence(resp.content))
        plan = PlanDraft.model_validate(data)
        return plan if plan.usable() else None
    except Exception as e:
        logger.warning("[ENVELOPE] 计划修复重抽失败: %s", e)
        return None
