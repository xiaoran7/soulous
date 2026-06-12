from typing import TypedDict, Annotated, Sequence, Optional, List, Dict, Any
from langchain_core.messages import BaseMessage, SystemMessage
from langgraph.graph.message import add_messages
from pydantic import BaseModel, Field

class RuntimeStateSchema(BaseModel):
    """
    运行状态强类型模型。
    用于防止由于字段拼写错误或类型混淆导致的状态失效。
    """
    current_query: str = Field("", description="当前轮次的用户提问")
    user_id: str = Field("", description="Soulous 用户 ID（多租户隔离主键）")
    session_active: bool = Field(True, description="当前会话是否仍在进行中")
    force_summarize: bool = Field(False, description="用于测试的强制提炼摘要标志")
    budget_exceeded: bool = Field(False, description="上下文是否超出了设定的 token 预算")
    current_tool_call_id: Optional[str] = Field(None, description="当前正在执行的 tool-call 唯一标识")
    attachments: List[Dict[str, str]] = Field(default_factory=list, description="本轮附件文本（name/text），独立 document 段注入")
    soulous_context: Dict[str, Any] = Field(default_factory=dict, description="Soulous 业务画像投影（goal/pet/streak/task_context）")

class AgentState(TypedDict):
    """
    多通道、高强度的智能体全局状态拓扑定义。
    底线设计：将对话、思维链、工具流在数据结构上彻底分离，有效精简模型上下文并解耦前端展示。
    """
    # 1. 纯净的人机问答记录 (前端仅需提取本通道进行展示)
    conversation_messages: Annotated[Sequence[BaseMessage], add_messages]

    # 2. 中间推理思考链记录 (本轮结束后将在 memory_node 中执行蒸馏持久化并清空)
    reasoning_messages: Annotated[Sequence[BaseMessage], add_messages]

    # 3. 工具交互详细载荷记录
    tool_messages: Annotated[Sequence[BaseMessage], add_messages]

    # 4. 组装好的系统级 SystemMessage，用于注入给模型作为头部指令
    system_context: Optional[SystemMessage]

    # 5. 会话级历史消息提炼出的全局摘要
    history_summary: str

    # 6. Pydantic 结构化运行时字段集
    runtime_state: RuntimeStateSchema

    # 7. 防止死循环的迭代计数器
    loop_count: int
