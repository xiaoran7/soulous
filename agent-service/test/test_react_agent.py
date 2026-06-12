"""
ReActLoop 全链路测试（mock LLM，零网络）：
多轮对话、PLAN_JSON 结构化抽取、流式 token、多租户隔离、DAL 审计落库。
"""
import sqlite3

import pytest

from app.core.loop.react_loop import ReActLoop
from app.providers.mock_llm import MockChatModel
from app.schemas.models import ChatRequest


@pytest.fixture
async def loop(tmp_path, vector_service):
    loop = ReActLoop(
        MockChatModel(), vector_service,
        state_db=str(tmp_path / "state.db"),
        dal_db=str(tmp_path / "dal.db"),
    )
    await loop.setup()
    yield loop
    await loop.shutdown()


async def test_chat_returns_structured_plan(loop):
    req = ChatRequest(user_id="u1", conversation_id="c1", message="帮我拆解数据结构学习计划")
    result = await loop.run_chat(req)
    assert "PLAN_JSON" in result.reply
    assert result.plan is not None
    assert result.plan.category == "模拟学习"
    assert len(result.plan.tasks) == 3


async def test_plain_chat_no_plan(loop):
    req = ChatRequest(user_id="u1", conversation_id="c2", message="链表是什么？")
    result = await loop.run_chat(req)
    assert result.plan is None
    assert result.reply


async def test_stream_chat_tokens_and_done(loop):
    req = ChatRequest(user_id="u1", conversation_id="c3", message="帮我拆解英语口语学习计划")
    tokens, done = [], None
    async for evt in loop.stream_chat(req):
        if evt["type"] == "token":
            tokens.append(evt["text"])
        else:
            done = evt["result"]
    assert tokens, "应有增量 token"
    assert done is not None and done.plan is not None
    assert "".join(tokens) == done.reply  # 流式累积与终答一致


async def test_thread_isolation(loop, tmp_path):
    await loop.run_chat(ChatRequest(user_id="u1", conversation_id="c4", message="帮我拆解计划"))
    await loop.run_chat(ChatRequest(user_id="u2", conversation_id="c4", message="链表是什么？"))
    # 不同用户同 conversation_id 走不同 thread：u2 的状态里不应有 u1 的消息
    state_u2 = await loop.compiled.aget_state(
        {"configurable": {"thread_id": "u2:c4"}})
    contents = [m.content for m in state_u2.values["conversation_messages"]]
    assert all("拆解计划" not in c for c in contents)


async def test_interaction_logged_to_dal(loop, tmp_path):
    await loop.run_chat(ChatRequest(user_id="u1", conversation_id="c5", message="链表是什么？"))
    with sqlite3.connect(str(tmp_path / "dal.db")) as conn:
        rows = conn.execute("SELECT session_id, query FROM interaction_logs").fetchall()
    assert ("u1:c5", "链表是什么？") in rows


async def test_multi_turn_keeps_context(loop):
    await loop.run_chat(ChatRequest(user_id="u3", conversation_id="c6", message="链表是什么？"))
    result = await loop.run_chat(ChatRequest(user_id="u3", conversation_id="c6", message="再讲讲双向的"))
    assert result.reply
    state = await loop.compiled.aget_state({"configurable": {"thread_id": "u3:c6"}})
    # 两轮共 4 条对话消息（human/ai 各两条），reasoning/tool 通道已被 memory_node 清空
    assert len(state.values["conversation_messages"]) == 4
    assert len(state.values["reasoning_messages"]) == 0
    assert len(state.values["tool_messages"]) == 0
