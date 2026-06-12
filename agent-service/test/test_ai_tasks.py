import pytest

from app.providers.mock_llm import MockChatModel
from app.schemas.models import (DailyReviewRequest, ReviewRequest,
                                ReviewSubmission, ReviewTask)
from app.services import ai_tasks


@pytest.fixture
def llm():
    return MockChatModel()


async def test_review_submission_structured(llm, vector_service):
    req = ReviewRequest(
        user_id="u1",
        task=ReviewTask(title="学习链表", description="完成链表一章", task_type="STUDY", base_exp=20),
        submission=ReviewSubmission(text_proof="我学习了单链表和双链表，并实现了反转。", study_minutes=40),
    )
    verdict = await ai_tasks.review_submission(llm, vector_service, req)
    assert verdict is not None
    assert verdict.result == "PASS"
    assert 0 <= verdict.score <= 100
    assert verdict.recommended_exp <= 20


async def test_daily_review_structured(llm, vector_service):
    req = DailyReviewRequest(user_id="u1", completed_tasks=2, submissions=3,
                             study_minutes=90, earned_exp=40)
    result = await ai_tasks.daily_review(llm, vector_service, req)
    assert result is not None
    assert result.title
    assert result.highlights
    assert result.pet_message


async def test_daily_review_stream_suppresses_envelope(llm, vector_service):
    req = DailyReviewRequest(user_id="u1", completed_tasks=1)
    tokens, done = [], None
    async for evt in ai_tasks.daily_review_stream(llm, vector_service, req):
        if evt["type"] == "token":
            tokens.append(evt["text"])
        else:
            done = evt["result"]
    narration = "".join(tokens)
    assert "<REVIEW_JSON>" not in narration  # 信封被拦下，用户只看到自然语言
    assert "今天的节奏不错" in narration
    assert done is not None and done.title
