from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from app.core.context.context_builder import ContextProjectionLayer


def test_tool_messages_interleave_after_their_tool_calls():
    """ToolMessage 必须紧跟其所属 assistant(tool_calls) 消息（OpenAI 兼容 API 硬约束）。"""
    call1 = AIMessage(content="", tool_calls=[
        {"name": "query_pet_status", "args": {}, "id": "tc1"}])
    call2 = AIMessage(content="", tool_calls=[
        {"name": "query_timetable", "args": {}, "id": "tc2"},
        {"name": "query_focus_history", "args": {}, "id": "tc3"}])
    state = {
        "system_context": SystemMessage(content="sys"),
        "conversation_messages": [HumanMessage(content="hi")],
        "reasoning_messages": [call1, call2],
        # 故意乱序，验证按 tool_call_id 配对而非按列表顺序
        "tool_messages": [
            ToolMessage(content="r3", name="query_focus_history", tool_call_id="tc3"),
            ToolMessage(content="r1", name="query_pet_status", tool_call_id="tc1"),
            ToolMessage(content="r2", name="query_timetable", tool_call_id="tc2"),
        ],
    }
    payload = ContextProjectionLayer.project(state)
    kinds = [(m.type, getattr(m, "tool_call_id", None)) for m in payload]
    assert kinds == [
        ("system", None), ("human", None),
        ("ai", None), ("tool", "tc1"),
        ("ai", None), ("tool", "tc2"), ("tool", "tc3"),
    ]


def test_projection_without_tools():
    state = {
        "system_context": SystemMessage(content="sys"),
        "conversation_messages": [HumanMessage(content="hi"), AIMessage(content="hello")],
        "reasoning_messages": [],
        "tool_messages": [],
    }
    payload = ContextProjectionLayer.project(state)
    assert [m.type for m in payload] == ["system", "human", "ai"]
