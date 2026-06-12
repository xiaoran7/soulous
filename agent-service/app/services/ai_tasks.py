"""
单发 AI 任务（不走状态图）：任务凭证审核 + 每日复盘。

两者都是「一次结构化生成」而非多轮对话，直接调 LLM + Pydantic 校验 + 一次自纠重试，
对齐 Java 侧 completeJsonValidated 的语义。RAG 上下文从 VectorService 注入。
"""
import json
import logging
from typing import AsyncIterator, Dict, Optional

from langchain_core.messages import HumanMessage, SystemMessage

from app.core.context.context_builder import SOULOUS_RAG_TYPES
from app.core.context.rag.vector_service import VectorService
from app.core.loop.envelopes import extract_tag_json, strip_json_fence
from app.schemas.models import (DailyReviewRequest, DailyReviewResult,
                                ReviewRequest, ReviewVerdict)

logger = logging.getLogger("AiTasks")

RUBRICS = {
    "CODING": "评分准则【编程任务】：必须看到代码片段或运行截图；qualityScore 主要看实现正确性、关键算法/数据结构与可运行性，缺少代码或截图时 qualityScore 不应高于 50；纯文字描述不应给出 PASS。",
    "STUDY": "评分准则【理论/笔记任务】：以文字总结的深度与条理为主要评分依据；不要求代码或截图，只要看到具体知识点、概念对比或个人理解即可给出较高分。",
    "NOTE": "评分准则【理论/笔记任务】：以文字总结的深度与条理为主要评分依据；不要求代码或截图，只要看到具体知识点、概念对比或个人理解即可给出较高分。",
    "MEMORY": "评分准则【记忆/背诵任务】：以用户用自己的话复述或要点列举的完整度为评分核心；不强求截图或代码。",
    "REVIEW": "评分准则【复盘任务】：以反思深度为主——是否指出问题、原因、改进措施；文字描述充分即可。",
    "SIMPLE": "评分准则【简单任务】：只要内容真实且与任务相关即可通过；评分上限 70 分，避免给出 80 分以上的高分。",
}


def _rag_block(vector_service: Optional[VectorService], user_id: str, query: str) -> str:
    if not vector_service or not vector_service.available or not query.strip():
        return ""
    try:
        hits = vector_service.search(user_id, query, k=3, source_types=SOULOUS_RAG_TYPES)
    except Exception:
        return ""
    if not hits:
        return ""
    lines = ["\n[用户历史相关记忆]"]
    for i, h in enumerate(hits):
        lines.append(f"[{i + 1}] {h['source_type']}（相似度 {h['similarity']:.2f}）：{h['text'][:400]}")
    lines.append("（请把以上历史作为参考语境，但以本次提交本身的事实为准）")
    return "\n".join(lines)


async def _json_with_retry(llm, system: str, user: str, validate) -> Optional[dict]:
    """JSON 生成 + Pydantic 校验，失败带错误反馈自纠重试一次。"""
    attempt_user = user
    for attempt in range(2):
        try:
            resp = await llm.ainvoke([SystemMessage(content=system), HumanMessage(content=attempt_user)])
            data = json.loads(strip_json_fence(resp.content))
            return validate(data)
        except Exception as e:
            logger.warning("[AI_TASK] JSON 生成第 %d 次失败: %s", attempt + 1, e)
            attempt_user = user + f"\n\n（你上一次的输出无法解析为合法 JSON：{e}。请只输出一个严格合法的 JSON 对象，不要任何解释或围栏。）"
    return None


# --------------------------------------------------------------- review ----

async def review_submission(llm, vector_service: Optional[VectorService],
                            req: ReviewRequest) -> Optional[ReviewVerdict]:
    """任务凭证审核：结构化裁决；LLM 不可用/两次解析失败时返回 None（Java 走规则兜底）。"""
    task, sub = req.task, req.submission
    base_exp = max(1, task.base_exp)
    rubric = RUBRICS.get(task.task_type.upper(), "评分时综合考虑相关性、完整度、质量三方面。")
    rag = _rag_block(vector_service, req.user_id, f"{task.title} {task.description}")

    system = ("你是严谨但鼓励性的学习审核助手。根据用户提交的学习凭证给出评分和反馈。" + rubric
              + ' 返回 JSON，字段：result("PASS"|"NEED_MORE"|"REJECT")、relevanceScore(0-100)、'
                "completenessScore(0-100)、qualityScore(0-100)、score(0-100)、reason(中文 1-2 句)、"
                f"suggestion(中文 1-2 句)、recommendedExp(0-{base_exp})、needManual(boolean)。"
                "只输出 JSON 对象本身。" + rag)
    user = (f"任务：{task.title}\n描述：{task.description}\n类型：{task.task_type}，难度：{task.difficulty}，"
            f"课程：{task.course_name}，基础经验：{base_exp}\n\n用户提交：\n文字：{sub.text_proof}\n"
            f"代码：{sub.code_snippet}\n链接：{sub.proof_link}\n"
            f"截图：{'已上传' if sub.has_screenshot else '无'}\n学习时长（分）：{sub.study_minutes}")

    def validate(data: dict) -> ReviewVerdict:
        verdict = ReviewVerdict.model_validate(data)
        verdict.score = min(max(verdict.score, 0), 100)
        verdict.recommended_exp = min(max(verdict.recommended_exp, 0), base_exp)
        if verdict.result not in ("PASS", "NEED_MORE", "REJECT", "MANUAL"):
            verdict.result = "NEED_MORE"
        return verdict

    return await _json_with_retry(llm, system, user, validate)


# --------------------------------------------------------- daily review ----

def _daily_review_prompts(req: DailyReviewRequest, rag: str) -> Dict[str, str]:
    system = ("你是温和、具体的学习教练。请按以下两段格式给出今日复盘：\n"
              "第一部分：用 80-160 字的自然语言总览，温暖地告诉用户他们今天的状态、亮点与建议"
              "（直接面向用户写，不要列表）。\n"
              "第二部分：紧接着输出 <REVIEW_JSON>{...}</REVIEW_JSON>，包含字段：title(短句标题)、"
              "summary(2-3 句总览)、highlights(2-4 条亮点)、risks(1-3 条需要留意)、"
              "tomorrowSuggestions(2-3 条明日建议)、petMessage(对宠物的话, 1 句)。"
              "envelope 前后不要有 Markdown 围栏。" + rag)
    lines = ["今日数据：",
             f"- 完成任务数：{req.completed_tasks}",
             f"- 提交凭证数：{req.submissions}",
             f"- 学习分钟：{req.study_minutes}",
             f"- 获得经验：{req.earned_exp}",
             f"- 被打回任务数：{req.rejected_count}"]
    if req.representative_completed:
        lines.append(f"- 完成的代表任务：{req.representative_completed}")
    if req.need_fix:
        lines.append(f"- 需要补充的任务：{req.need_fix}")
    if req.in_progress:
        lines.append(f"- 未完成进行中的任务：{req.in_progress}")
    if req.pet:
        lines.append(f"- 宠物：{req.pet.name}，Lv.{req.pet.level}，状态 {req.pet.status}")
    return {"system": system, "user": "\n".join(lines)}


def _parse_daily_review(reply: str) -> Optional[DailyReviewResult]:
    data = extract_tag_json(reply, "REVIEW_JSON")
    if data is None:
        return None
    try:
        return DailyReviewResult.model_validate(data)
    except Exception as e:
        logger.warning("[AI_TASK] REVIEW_JSON 校验失败: %s", e)
        return None


async def daily_review(llm, vector_service: Optional[VectorService],
                       req: DailyReviewRequest) -> Optional[DailyReviewResult]:
    """非流式每日复盘。失败返回 None（Java 走规则兜底）。"""
    rag = _rag_block(vector_service, req.user_id,
                     " ".join(filter(None, [req.representative_completed, req.need_fix, req.in_progress])))
    prompts = _daily_review_prompts(req, rag)
    try:
        resp = await llm.ainvoke([SystemMessage(content=prompts["system"]),
                                  HumanMessage(content=prompts["user"])])
        return _parse_daily_review(resp.content)
    except Exception as e:
        logger.warning("[AI_TASK] 每日复盘生成失败: %s", e)
        return None


async def daily_review_stream(llm, vector_service: Optional[VectorService],
                              req: DailyReviewRequest) -> AsyncIterator[Dict]:
    """
    流式每日复盘：token 事件只含自然语言叙述（REVIEW_JSON 信封在 agent 侧拦下），
    结束时产出 done 事件携带结构化结果。
    """
    rag = _rag_block(vector_service, req.user_id,
                     " ".join(filter(None, [req.representative_completed, req.need_fix, req.in_progress])))
    prompts = _daily_review_prompts(req, rag)
    full, emitted = "", 0
    try:
        async for chunk in llm.astream([SystemMessage(content=prompts["system"]),
                                        HumanMessage(content=prompts["user"])]):
            text = getattr(chunk, "content", "")
            if not text:
                continue
            full += text
            open_idx = full.find("<REVIEW_JSON>")
            emit_up_to = open_idx if open_idx >= 0 else len(full)
            if emit_up_to > emitted:
                yield {"type": "token", "text": full[emitted:emit_up_to]}
                emitted = emit_up_to
    except Exception as e:
        logger.warning("[AI_TASK] 流式复盘失败: %s", e)
        yield {"type": "done", "result": None}
        return
    yield {"type": "done", "result": _parse_daily_review(full)}
