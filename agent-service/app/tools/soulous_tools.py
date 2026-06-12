"""
Soulous 业务工具集：替代骨架的 calculator 演示工具。

每个工具通过 service-token 回调 Spring Boot 的 /internal API 取业务事实，
agent 不直接连业务数据库。user_id 由请求上下文（contextvar）注入，
LLM 无法伪造他人身份——这是多租户隔离的工具层闸门。
"""
import json
import logging
from contextvars import ContextVar

import httpx
from langchain_core.tools import tool

logger = logging.getLogger("SoulousTools")

# 每请求注入的用户身份（在 react_loop 入口 set，工具内读取）
current_user_id: ContextVar[str] = ContextVar("current_user_id", default="")

_base_url = ""
_token = ""


def configure(base_url: str, token: str):
    global _base_url, _token
    _base_url = base_url.rstrip("/")
    _token = token


def _get(path: str) -> str:
    user_id = current_user_id.get()
    if not user_id:
        return "（无法确定当前用户，工具不可用）"
    if not _base_url:
        return "（Soulous 内部 API 未配置，工具不可用）"
    try:
        resp = httpx.get(
            f"{_base_url}{path}",
            params={"userId": user_id},
            headers={"X-Service-Token": _token},
            timeout=5.0,
        )
        if resp.status_code == 200:
            return json.dumps(resp.json(), ensure_ascii=False)
        return f"（查询失败：HTTP {resp.status_code}）"
    except Exception as e:
        logger.warning("[TOOL] 调用 %s 失败: %s", path, e)
        return f"（查询失败：{e}）"


@tool
def query_focus_history() -> str:
    """查询当前用户近 7 天的专注学习时长统计（每日分钟数与合计）。当用户问到自己最近学得怎么样、专注情况时调用。"""
    return _get("/internal/agent/focus-history")


@tool
def query_timetable() -> str:
    """查询当前用户本周课表与近期考试安排。当用户问到上课时间、规划要避开的课程、考试时间时调用。"""
    return _get("/internal/agent/timetable")


@tool
def query_pet_status() -> str:
    """查询当前用户的出战宠物状态（名字、等级、心情、连续打卡天数）。当用户问到宠物或需要据此给出激励时调用。"""
    return _get("/internal/agent/pet-status")


SOULOUS_TOOLS = [query_focus_history, query_timetable, query_pet_status]
