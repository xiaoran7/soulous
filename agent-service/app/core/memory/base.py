import time
import math
from pydantic import BaseModel, Field
from typing import Dict, Any, List, Optional

class MemoryItem(BaseModel):
    """
    情景/事件记忆条目数据模型，包含时间戳、访问频次和元数据。
    """
    id: str = Field(..., description="记忆条目的唯一标识符")
    content: str = Field(..., description="记忆的具体文本内容")
    timestamp: float = Field(default_factory=time.time, description="记忆被创建时的时间戳")
    importance: float = Field(default=1.0, description="记忆的基础重要性评分 (1.0 至 10.0)")
    access_count: int = Field(default=1, description="该记忆被成功提取/引用的累计频次")
    last_accessed: float = Field(default_factory=time.time, description="最近一次被成功提取/引用的时间戳")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="关联的上下文元数据（例如：session_id, user_id, 标签等）")

def calculate_memory_weight(
    item: MemoryItem,
    current_time: float,
    relevance_score: float = 0.0,
    half_life_days: float = 30.0,
    w_recency: float = 0.3,
    w_importance: float = 0.3,
    w_relevance: float = 0.4
) -> float:
    """
    规划记忆权值的核心函数。
    结合了时间衰减（Recency）、原发重要性（Importance）以及当前检索相关度（Relevance）。
    时间衰减用天级半衰期（0.5^(ageDays/halfLife)），与 Soulous RAG 的衰减公式同构；
    原实现按秒级指数衰减（decay_rate=0.005/s），记忆两分钟即腰斩，已修正。
    """
    # 1. 计算时间衰减（半衰期遗忘曲线）
    age_days = max(0.0, current_time - item.last_accessed) / 86400.0
    recency_score = 0.5 ** (age_days / max(half_life_days, 0.01))

    # 2. 归一化重要性权重 (将 1-10 映射到 0.1-1.0)
    importance_score = min(max(item.importance / 10.0, 0.1), 1.0)

    # 3. 计算多维加权总和
    weight = (w_recency * recency_score) + (w_importance * importance_score) + (w_relevance * relevance_score)
    return weight

class UserProfile(BaseModel):
    """
    用户画像模型，仅包含稳定背景与偏好设置。
    优化设计：长期的 Episodic 情景记忆已被剥离出统一的 JSON，使用 Store 独立 Namespace ('episodes', user_id) 存储。
    """
    user_id: str = Field(..., description="用户的唯一标识符")
    background: Optional[str] = Field(None, description="用户的个人背景与职业描述")
    preferences: Dict[str, Any] = Field(default_factory=dict, description="用户的核心长期偏好设置")
