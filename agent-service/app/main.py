"""
agent-service FastAPI 入口。

- lifespan 单例：LLM / VectorService / ReActLoop（含 AsyncSqliteSaver 长连接）启动时构建一次；
- 鉴权：除 /health 外所有端点要求 X-Service-Token 与 AGENT_SERVICE_TOKEN 匹配（内网 Spring 专用）；
- SSE：/agent/chat/stream 与 /agent/daily-review/stream 推 token/plan/clarify/done/error 事件。
"""
import asyncio
import json
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse

from app.config import get_settings
from app.core.context.rag.vector_service import VectorService
from app.core.guardrail.guardrail import Guardrail
from app.core.loop.react_loop import ReActLoop
from app.providers.llm_provider import get_llm
from app.schemas.models import (ChatRequest, ChatResult, DailyReviewRequest,
                                DailyReviewResult, RagDeleteRequest, RagHit,
                                RagSearchRequest, RagUpsertRequest,
                                ReviewRequest, ReviewVerdict)
from app.services import ai_tasks
from app.tools import soulous_tools

logger = logging.getLogger("AgentService")

settings = get_settings()
loop: ReActLoop = None  # lifespan 内初始化
vector_service: VectorService = None
llm = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global loop, vector_service, llm
    llm = get_llm(temperature=0.3)
    vector_service = VectorService(
        db_path=settings.rag_db,
        api_key=settings.embedding_api_key,
        base_url=settings.embedding_base_url,
        model=settings.embedding_model,
        dimension=settings.embedding_dimension,
    )
    Guardrail.configure(settings.soulous_base_url, settings.soulous_service_token)
    soulous_tools.configure(settings.soulous_base_url, settings.soulous_service_token)
    loop = ReActLoop(
        llm, vector_service,
        state_db=settings.state_db, dal_db=settings.dal_db,
        system_limit=settings.budget_system_tokens,
        conversation_limit=settings.budget_conversation_tokens,
        scratch_limit=settings.budget_scratch_tokens,
    )
    await loop.setup()
    logger.info("[BOOT] agent-service 就绪（LLM=%s, RAG=%s）。",
                type(llm).__name__, "可用" if vector_service.available else "未配置")
    yield
    await loop.shutdown()


app = FastAPI(title="Soulous Agent Service", lifespan=lifespan)


@app.middleware("http")
async def service_token_auth(request: Request, call_next):
    if request.url.path != "/health" and settings.service_token:
        if request.headers.get("X-Service-Token") != settings.service_token:
            from fastapi.responses import JSONResponse
            return JSONResponse(status_code=401, content={"detail": "invalid service token"})
    return await call_next(request)


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _chat_done_payload(result: ChatResult) -> dict:
    return result.model_dump(by_alias=True, exclude_none=True)


# ----------------------------------------------------------------- chat ----

@app.post("/agent/chat")
async def chat(req: ChatRequest) -> ChatResult:
    try:
        return await loop.run_chat(req)
    except Exception as e:
        logger.exception("chat 失败")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/agent/chat/stream")
async def chat_stream(req: ChatRequest):
    async def gen():
        try:
            async for evt in loop.stream_chat(req):
                if evt["type"] == "token":
                    yield _sse("token", {"text": evt["text"]})
                elif evt["type"] == "done":
                    yield _sse("done", _chat_done_payload(evt["result"]))
        except Exception as e:
            logger.exception("chat stream 失败")
            yield _sse("error", {"message": str(e)})
    return StreamingResponse(gen(), media_type="text/event-stream")


# --------------------------------------------------------------- review ----

@app.post("/agent/review")
async def review(req: ReviewRequest) -> ReviewVerdict:
    verdict = await ai_tasks.review_submission(llm, vector_service, req)
    if verdict is None:
        raise HTTPException(status_code=502, detail="LLM review unavailable")
    return verdict


# --------------------------------------------------------- daily review ----

@app.post("/agent/daily-review")
async def daily_review(req: DailyReviewRequest) -> DailyReviewResult:
    result = await ai_tasks.daily_review(llm, vector_service, req)
    if result is None:
        raise HTTPException(status_code=502, detail="LLM daily review unavailable")
    return result


@app.post("/agent/daily-review/stream")
async def daily_review_stream(req: DailyReviewRequest):
    async def gen():
        try:
            async for evt in ai_tasks.daily_review_stream(llm, vector_service, req):
                if evt["type"] == "token":
                    yield _sse("token", {"text": evt["text"]})
                elif evt["type"] == "done":
                    payload = evt["result"].model_dump(by_alias=True) if evt["result"] else {}
                    yield _sse("done", payload)
        except Exception as e:
            logger.exception("daily review stream 失败")
            yield _sse("error", {"message": str(e)})
    return StreamingResponse(gen(), media_type="text/event-stream")


# ------------------------------------------------------------------ rag ----

@app.post("/rag/upsert")
async def rag_upsert(req: RagUpsertRequest):
    ok = await asyncio.to_thread(
        vector_service.upsert, req.user_id, req.source_type, req.source_id,
        req.text, req.importance)
    return {"ok": ok}


@app.post("/rag/search")
async def rag_search(req: RagSearchRequest) -> list[RagHit]:
    hits = await asyncio.to_thread(
        vector_service.search, req.user_id, req.query, req.k, req.source_types)
    return [RagHit.model_validate(h) for h in hits]


@app.post("/rag/delete")
async def rag_delete(req: RagDeleteRequest):
    deleted = await asyncio.to_thread(
        vector_service.delete, req.user_id, req.source_type, req.source_id)
    return {"deleted": deleted}


# --------------------------------------------------------------- health ----

@app.get("/health")
async def health():
    return {
        "status": "UP",
        "llm": type(llm).__name__ if llm else None,
        "embedding": bool(vector_service and vector_service.available),
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host=settings.host, port=settings.port)
