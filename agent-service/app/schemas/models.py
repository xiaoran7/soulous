"""
agent-service 对外契约（Pydantic 结构化输入/输出）。

约定：JSON 线格式一律 camelCase（与 Spring Boot 侧 Jackson 默认风格一致），
Python 侧字段 snake_case，经 alias_generator 自动映射。
"""
from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


# ---------------------------------------------------------------- chat ----

class Attachment(ApiModel):
    """用户上传附件（前端已提取的 md/pdf/txt 纯文本）。"""
    name: str = ""
    text: str = ""


class UserContext(ApiModel):
    """Soulous 业务画像投影：由 Spring 随请求带入，agent 不直接查业务库。"""
    goal: Optional[str] = None
    pet_name: Optional[str] = None
    pet_level: Optional[int] = None
    streak: Optional[int] = None
    task_context: Optional[str] = None


class ChatRequest(ApiModel):
    user_id: str
    conversation_id: str
    message: str
    attachments: List[Attachment] = Field(default_factory=list)
    context: Optional[UserContext] = None


class PlanTask(ApiModel):
    title: str
    description: str = ""
    estimated_minutes: int = 30
    difficulty: str = "NORMAL"
    task_type: str = "STUDY"
    base_exp: int = 20


class PlanDraft(ApiModel):
    """与 Java 侧 PLAN_JSON 信封同构的计划草案。"""
    category: str = ""
    tasks: List[PlanTask] = Field(default_factory=list)

    def usable(self) -> bool:
        return any(t.title.strip() for t in self.tasks)


class ClarifyOption(ApiModel):
    label: str
    hint: Optional[str] = None


class ClarifyQuestion(ApiModel):
    id: str = ""
    question: str
    multi_select: bool = False
    options: List[ClarifyOption] = Field(default_factory=list)


class ClarifyDraft(ApiModel):
    questions: List[ClarifyQuestion] = Field(default_factory=list)

    def usable(self) -> bool:
        return any(q.question.strip() and q.options for q in self.questions)


class ChatResult(ApiModel):
    """非流式（或流式 done 事件）的最终回包。"""
    reply: str
    plan: Optional[PlanDraft] = None
    clarify: Optional[ClarifyDraft] = None


# -------------------------------------------------------------- review ----

class ReviewTask(ApiModel):
    title: str = ""
    description: str = ""
    task_type: str = "STUDY"
    difficulty: str = "NORMAL"
    course_name: str = ""
    base_exp: int = 20


class ReviewSubmission(ApiModel):
    text_proof: str = ""
    code_snippet: str = ""
    proof_link: str = ""
    has_screenshot: bool = False
    study_minutes: int = 0


class ReviewRequest(ApiModel):
    user_id: str
    task: ReviewTask
    submission: ReviewSubmission


class ReviewVerdict(ApiModel):
    """与 Java AiReview 字段一一对应的结构化裁决。"""
    result: str = "NEED_MORE"  # PASS | NEED_MORE | REJECT | MANUAL
    relevance_score: int = 0
    completeness_score: int = 0
    quality_score: int = 0
    score: int = 0
    reason: str = ""
    suggestion: str = ""
    recommended_exp: int = 0
    need_manual: bool = False


# -------------------------------------------------------- daily review ----

class DailyReviewPet(ApiModel):
    name: str = ""
    level: int = 1
    status: str = "NORMAL"


class DailyReviewRequest(ApiModel):
    user_id: str
    completed_tasks: int = 0
    submissions: int = 0
    study_minutes: int = 0
    earned_exp: int = 0
    rejected_count: int = 0
    representative_completed: Optional[str] = None
    need_fix: Optional[str] = None
    in_progress: Optional[str] = None
    pet: Optional[DailyReviewPet] = None


class DailyReviewResult(ApiModel):
    title: str = ""
    summary: str = ""
    highlights: List[str] = Field(default_factory=list)
    risks: List[str] = Field(default_factory=list)
    tomorrow_suggestions: List[str] = Field(default_factory=list)
    pet_message: str = ""


# ----------------------------------------------------------------- rag ----

class RagUpsertRequest(ApiModel):
    user_id: str
    source_type: str  # GOAL_MEMORY | SESSION_SUMMARY | COMPLETED_TASK | DAILY_REVIEW
    source_id: int
    text: str
    importance: float = 5.0


class RagSearchRequest(ApiModel):
    user_id: str
    query: str
    k: int = 3
    source_types: Optional[List[str]] = None


class RagHit(ApiModel):
    source_type: str
    source_id: int
    text: str
    similarity: float
    weight: float


class RagDeleteRequest(ApiModel):
    user_id: str
    source_type: Optional[str] = None
    source_id: Optional[int] = None
