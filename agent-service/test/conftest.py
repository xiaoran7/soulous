import sys
from pathlib import Path

import pytest

# 保证 `from app...` 可导入（无论从哪个目录跑 pytest）
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.context.rag.vector_service import VectorService  # noqa: E402


class FakeEmbeddingVectorService(VectorService):
    """
    测试替身：embedding 用确定性词袋向量（无网络），其余行为与真实实现完全一致。
    词表固定，文本与词表项有交集时向量相似。
    """
    VOCAB = ["数据", "结构", "链表", "数学", "英语", "口语", "算法", "复盘", "宠物", "计划"]

    def __init__(self, db_path: str):
        super().__init__(db_path=db_path, api_key="", dimension=len(self.VOCAB))
        self._fake_available = True

    @property
    def available(self) -> bool:
        return self._fake_available

    def get_embedding(self, text: str):
        if not self._fake_available:
            return None
        vec = [float(text.count(w)) for w in self.VOCAB]
        norm = sum(v * v for v in vec) ** 0.5
        if norm == 0:
            # 与词表无交集：返回一个正交占位向量，避免除零
            vec[0] = 1e-6
            norm = 1e-6
        return [v / norm for v in vec]


@pytest.fixture
def vector_service(tmp_path):
    return FakeEmbeddingVectorService(str(tmp_path / "rag.db"))
