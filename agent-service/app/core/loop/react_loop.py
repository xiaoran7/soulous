"""
基于 LangGraph StateGraph 的认知控制循环（服务化异步重构版）。

相对骨架原版的关键改造：
  1. 全异步节点 + 应用生命周期单例：graph 启动时 compile 一次，AsyncSqliteSaver 长连接，
     支持多用户并发（原版每次 run() 重开连接、重新 compile，无法并发）；
  2. 多租户：thread_id = "{user_id}:{conversation_id}"，所有持久化按 user_id 命名空间隔离；
  3. SqliteStore 退役：用户画像持久化归 DAL，情景记忆归 sqlite-vec 向量库
     （检索自带 embedding relevance，淘汰字符集交集近似）；
  4. 流式：通过 graph.astream_events 透出 agent 主调用的增量 token（tag 过滤，
     摘要/蒸馏等内部调用不向外泄漏）；
  5. 结构化输出：最终回复过 envelopes 模块的 Pydantic 校验与修复重抽。
"""
import asyncio
import json
import logging
import time
from typing import Any, AsyncIterator, Dict, Optional, Tuple

import aiosqlite
from langchain_core.messages import AIMessage, HumanMessage, RemoveMessage, ToolMessage
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langgraph.graph import END, StateGraph

from app.core.context.context_builder import ContextBuilder, ContextProjectionLayer, EPISODE_TYPE
from app.core.context.rag.vector_service import VectorService
from app.core.guardrail.guardrail import Guardrail
from app.core.loop import envelopes
from app.core.loop.dal import DataAccessLayer
from app.core.loop.state import AgentState, RuntimeStateSchema
from app.core.memory.base import UserProfile
from app.schemas.models import ChatRequest, ChatResult
from app.tools.soulous_tools import SOULOUS_TOOLS, current_user_id

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(name)s - [%(levelname)s] - %(message)s")
logger = logging.getLogger("ReActLoop")

MAIN_LLM_TAG = "agent-main"


class ReActLoop:
    def __init__(self, llm, vector_service: VectorService,
                 max_iterations: int = 5,
                 state_db: str = "data/agent_state.db",
                 dal_db: str = "data/agent_data.db",
                 system_limit: int = 2000, conversation_limit: int = 2400,
                 scratch_limit: int = 2000):
        self.llm = llm
        self.max_iterations = max_iterations
        self.state_db = state_db
        self.dal = DataAccessLayer(dal_db)
        self.vector_service = vector_service
        self.context_builder = ContextBuilder(
            vector_service, system_limit, conversation_limit, scratch_limit)

        self.tools_map = {t.name: t for t in SOULOUS_TOOLS}
        self.llm_with_tools = self.llm.bind_tools(SOULOUS_TOOLS).with_config(tags=[MAIN_LLM_TAG])

        self._conn: Optional[aiosqlite.Connection] = None
        self.compiled = None

    # ----------------------------------------------------------- lifecycle --

    async def setup(self):
        """应用启动时调用一次：建立长连接 checkpointer 并 compile 图。"""
        self._conn = await aiosqlite.connect(self.state_db)
        checkpointer = AsyncSqliteSaver(self._conn)
        await checkpointer.setup()
        self.compiled = self._build_graph().compile(checkpointer=checkpointer)
        logger.info("[LOOP] 状态图已编译，checkpointer 就绪（%s）。", self.state_db)

    async def shutdown(self):
        if self._conn:
            await self._conn.close()
            self._conn = None

    # --------------------------------------------------------------- graph --

    def _build_graph(self) -> StateGraph:
        workflow = StateGraph(AgentState)

        workflow.add_node("context_node", self._context_node)
        workflow.add_node("summarize_history", self._summarize_history)
        workflow.add_node("agent", self._call_model)
        workflow.add_node("action", self._execute_tool)
        workflow.add_node("memory_node", self._memory_node)

        workflow.set_entry_point("context_node")
        workflow.add_conditional_edges(
            "context_node", self._budget_router,
            {"exceeded": "summarize_history", "ok": "agent"})
        workflow.add_edge("summarize_history", "context_node")
        workflow.add_conditional_edges(
            "agent", self._should_continue,
            {"continue": "action", "end": "memory_node"})
        workflow.add_edge("action", "context_node")
        workflow.add_edge("memory_node", END)
        return workflow

    # --------------------------------------------------------------- nodes --

    async def _context_node(self, state: AgentState, config: RunnableConfig = None):
        """组装系统 Prompt（画像 / 情景记忆 / RAG / 附件）并评估 token 预算。"""
        runtime = state["runtime_state"]
        user_id = runtime.user_id or "default_user"

        # 画像从 DAL 恢复（SqliteStore 已退役）
        db_profile = await asyncio.to_thread(self.dal.get_user_profile, user_id)
        user_profile = UserProfile(
            user_id=user_id,
            background=db_profile.get("background"),
            preferences=db_profile.get("preferences", {}),
        ) if db_profile else None

        history_summary = state.get("history_summary", "")
        system_message = await asyncio.to_thread(
            self.context_builder.build_system_prompt, runtime, user_profile, history_summary)

        budget_exceeded = self.context_builder.budget_manager.check_budget(state)
        runtime_update = runtime.model_copy(update={"budget_exceeded": budget_exceeded})
        return {"system_context": system_message, "runtime_state": runtime_update}

    def _budget_router(self, state: AgentState) -> str:
        runtime = state["runtime_state"]
        if runtime.budget_exceeded or runtime.force_summarize:
            logger.info("[ROUTER] 消息预算超标或强制触发，路由至 summarize_history 瘦身。")
            return "exceeded"
        return "ok"

    async def _summarize_history(self, state: AgentState, config: RunnableConfig = None):
        """前置消息 Summary 提炼与 Pop 裁剪：折叠 conversation 老消息为 history_summary。"""
        conv_messages = state.get("conversation_messages", [])
        if len(conv_messages) <= 2:
            runtime_update = state["runtime_state"].model_copy(
                update={"force_summarize": False, "budget_exceeded": False})
            return {"runtime_state": runtime_update}

        messages_to_summarize = conv_messages[:-2]
        history_text = "\n".join(
            f"{'User' if m.type == 'human' else 'AI'}: {m.content}" for m in messages_to_summarize)
        summary_prompt = (
            "你是一个历史对话总结器。请用简明扼要的中文总结以下前序对话历史的核心要点，"
            "保留任务清单、已确认的拆解决策、用户偏好与未解疑问，作后续上下文参考：\n\n"
            f"{history_text}\n\n总结控制在 200 字以内，只输出摘要正文。")
        try:
            response = await self.llm.ainvoke([HumanMessage(content=summary_prompt)])
            summary = response.content
            old = state.get("history_summary", "")
            updated = f"{old}\n[前情追加]: {summary}" if old else summary
            remove = [RemoveMessage(id=m.id) for m in messages_to_summarize if m.id]
            logger.info("[SUMMARIZER] Pop 裁剪 %d 条历史消息。", len(remove))
            runtime_update = state["runtime_state"].model_copy(
                update={"force_summarize": False, "budget_exceeded": False})
            return {"conversation_messages": remove, "history_summary": updated,
                    "runtime_state": runtime_update}
        except Exception as e:
            logger.error("[SUMMARIZER] 提炼摘要失败: %s", e)
            runtime_update = state["runtime_state"].model_copy(
                update={"force_summarize": False, "budget_exceeded": False})
            return {"runtime_state": runtime_update}

    async def _call_model(self, state: AgentState, config: RunnableConfig = None):
        """Model 节点：guardrail → CPL 投影 → 决策；tool_calls 入 reasoning 流，终答入 conversation 流。"""
        runtime = state["runtime_state"]
        user_query = runtime.current_query
        if user_query and not await asyncio.to_thread(Guardrail.check_input, user_query):
            logger.warning("[GUARDRAIL] 输入未通过安全检查，已拦截。")
            return {"conversation_messages": [AIMessage(content="⚠️ 你的消息未通过内容安全检查，请调整后重试。")],
                    "loop_count": state["loop_count"] + 1}

        payload = ContextProjectionLayer.project(state)
        response = await self.llm_with_tools.ainvoke(payload)

        if getattr(response, "tool_calls", None):
            return {"reasoning_messages": [response], "loop_count": state["loop_count"] + 1}
        return {"conversation_messages": [response], "loop_count": state["loop_count"] + 1}

    async def _execute_tool(self, state: AgentState, config: RunnableConfig = None):
        """工具执行节点：user_id 经 contextvar 注入，LLM 无法越权查询他人数据。"""
        last_reasoning = state["reasoning_messages"][-1] if state["reasoning_messages"] else None
        if not last_reasoning or not getattr(last_reasoning, "tool_calls", None):
            return {}

        token = current_user_id.set(state["runtime_state"].user_id)
        try:
            outputs = []
            for tc in last_reasoning.tool_calls:
                name, args, call_id = tc["name"], tc["args"], tc["id"]
                logger.info("[TOOL] 执行: %s | 参数: %s", name, args)
                if name in self.tools_map:
                    try:
                        result = await asyncio.to_thread(self.tools_map[name].invoke, args)
                    except Exception as e:
                        result = f"工具调用异常: {e}"
                        logger.error(result)
                else:
                    result = f"未找到该工具: {name}"
                outputs.append(ToolMessage(content=str(result), name=name, tool_call_id=call_id))
            return {"tool_messages": outputs}
        finally:
            current_user_id.reset(token)

    def _should_continue(self, state: AgentState) -> str:
        if state.get("loop_count", 0) >= self.max_iterations:
            logger.warning("[ROUTER] 循环到达上限 %d 轮，强制终止。", self.max_iterations)
            return "end"
        if state["reasoning_messages"]:
            last = state["reasoning_messages"][-1]
            if getattr(last, "tool_calls", None):
                executed = {m.tool_call_id for m in state["tool_messages"]}
                if any(tc["id"] not in executed for tc in last.tool_calls):
                    return "continue"
        return "end"

    async def _memory_node(self, state: AgentState, config: RunnableConfig = None):
        """收尾节点：思维链蒸馏入 DAL、情景/语义记忆提取（情景→向量库、画像→DAL）、清空临时通道。"""
        runtime = state["runtime_state"]
        user_id = runtime.user_id or "default_user"
        session_id = (config or {}).get("configurable", {}).get("thread_id", "default_thread")
        query = runtime.current_query
        final_response = state["conversation_messages"][-1].content if state["conversation_messages"] else "无回答"

        # 1. 思维链蒸馏归档
        distilled = "无中间推理过程。"
        if state["reasoning_messages"]:
            steps = []
            for idx, msg in enumerate(state["reasoning_messages"]):
                steps.append(f"Step {idx + 1}: {msg.content or ''}")
                if getattr(msg, "tool_calls", None):
                    steps.append(f"  [计划执行工具]: {[tc['name'] for tc in msg.tool_calls]}")
            prompt = ("你是一个思维链蒸馏器。请用简明扼要的中文总结以下智能体决策路径"
                      "（决策动机、验证逻辑与最终发现）：\n\n" + "\n".join(steps) + "\n\n总结控制在 100 字以内。")
            try:
                distilled = (await self.llm.ainvoke([HumanMessage(content=prompt)])).content
            except Exception as e:
                logger.error("[MEMORY_NODE] 思维链蒸馏失败: %s", e)

        await asyncio.to_thread(
            self.dal.log_interaction, session_id, query, str(final_response), distilled)

        # 2. 情景/语义记忆提取（重要度评分）
        analysis_prompt = (
            "请分析以下这轮对话，提取值得长期记录的【情景记忆】(如确定了某个学习计划、解决了何种关键问题) "
            "和【稳定事实】(如用户表露的专业背景、持久学习偏好)。\n\n"
            f"用户问题: {query}\n智能体回答: {final_response}\n\n"
            "必须严格按照以下 JSON 格式返回，严禁包含 ```json 代码块包裹或任何多余文字：\n"
            '{"has_episodic": true/false, "episodic_content": "事件描述...", "importance": 1.0到10.0的浮点数,'
            ' "has_semantic": true/false, "semantic_background": "用户事实描述...", "semantic_preferences": {"偏好名": "偏好值"}}')
        try:
            raw = (await self.llm.ainvoke([HumanMessage(content=analysis_prompt)])).content
            data = json.loads(envelopes.strip_json_fence(raw))
        except Exception as e:
            logger.warning("[MEMORY_NODE] 记忆提炼 JSON 解析失败: %s，跳过。", e)
            data = {}

        if data.get("has_episodic") and data.get("episodic_content"):
            importance = float(data.get("importance", 5.0))
            ok = await asyncio.to_thread(
                self.vector_service.upsert, user_id, EPISODE_TYPE,
                int(time.time() * 1000), data["episodic_content"], importance)
            if ok:
                logger.info("[MEMORY_NODE] 情景记忆入向量库: %s (重要度 %.1f)",
                            data["episodic_content"], importance)

        if data.get("has_semantic"):
            profile = await asyncio.to_thread(self.dal.get_user_profile, user_id) or {}
            if data.get("semantic_background"):
                profile["background"] = data["semantic_background"]
            if data.get("semantic_preferences"):
                prefs = profile.get("preferences", {})
                prefs.update(data["semantic_preferences"])
                profile["preferences"] = prefs
            if profile:
                await asyncio.to_thread(self.dal.save_user_profile, user_id, profile)
                logger.info("[MEMORY_NODE] 画像更新已持久化。")

        # 3. 清空临时通道（Token 瘦身）并复位运行时
        remove_reasoning = [RemoveMessage(id=m.id) for m in state["reasoning_messages"] if m.id]
        remove_tools = [RemoveMessage(id=m.id) for m in state["tool_messages"] if m.id]
        runtime_reset = RuntimeStateSchema(user_id=user_id, current_query="", session_active=False)
        return {"reasoning_messages": remove_reasoning, "tool_messages": remove_tools,
                "runtime_state": runtime_reset}

    # ----------------------------------------------------------- entrypoints --

    def _initial_state(self, req: ChatRequest) -> Tuple[Dict[str, Any], Dict[str, Any]]:
        runtime = RuntimeStateSchema(
            current_query=req.message,
            user_id=req.user_id,
            session_active=True,
            attachments=[a.model_dump() for a in req.attachments],
            soulous_context=req.context.model_dump() if req.context else {},
        )
        state = {
            "conversation_messages": [HumanMessage(content=req.message)],
            "reasoning_messages": [],
            "tool_messages": [],
            "system_context": None,
            "history_summary": "",
            "runtime_state": runtime,
            "loop_count": 0,
        }
        config = {"configurable": {"thread_id": f"{req.user_id}:{req.conversation_id}"}}
        return state, config

    async def _finalize(self, final_state: Dict[str, Any]) -> ChatResult:
        """终态收口：抽取最终回复，结构化校验信封，必要时修复重抽 + 输出 guardrail。"""
        msgs = final_state.get("conversation_messages", [])
        reply = str(msgs[-1].content) if msgs else "（AI 暂不可用）请稍后重试。"

        if not await asyncio.to_thread(Guardrail.check_output, reply):
            return ChatResult(reply="（AI 回复已被安全系统拦截，请换一种方式提问。）")

        plan = envelopes.parse_plan(reply)
        clarify = None if plan else envelopes.parse_clarify(reply)
        if plan is None and clarify is None and envelopes.looks_like_plan_claim(reply):
            plan = await envelopes.repair_plan(self.llm, reply)
            if plan:
                logger.info("[LOOP] 通过修复重抽恢复了缺失的 PLAN_JSON 信封。")
        return ChatResult(reply=reply, plan=plan, clarify=clarify)

    async def run_chat(self, req: ChatRequest) -> ChatResult:
        """非流式入口。"""
        state, config = self._initial_state(req)
        final_state = await self.compiled.ainvoke(state, config)
        return await self._finalize(final_state)

    async def stream_chat(self, req: ChatRequest) -> AsyncIterator[Dict[str, Any]]:
        """
        流式入口：产出 {"type": "token", "text": ...} 增量与最终 {"type": "done", "result": ChatResult}。
        仅透出 agent 主调用（MAIN_LLM_TAG）的 token，摘要/蒸馏等内部调用不外泄。
        """
        state, config = self._initial_state(req)
        final_state = None
        async for event in self.compiled.astream_events(state, config, version="v2"):
            kind = event.get("event")
            if kind == "on_chat_model_stream" and MAIN_LLM_TAG in (event.get("tags") or []):
                chunk = event.get("data", {}).get("chunk")
                text = getattr(chunk, "content", "")
                if text:
                    yield {"type": "token", "text": text}
            elif kind == "on_chain_end" and event.get("name") == "LangGraph":
                final_state = event.get("data", {}).get("output")
        if final_state is None:
            final_state = await self.compiled.aget_state(config)
            final_state = final_state.values if final_state else {}
        result = await self._finalize(final_state)
        yield {"type": "done", "result": result}
