"""
实体化 RAG 检索服务（sqlite-vec 持久向量库）。

替代骨架原先的进程内 list 玩具实现：
  - 持久化：所有记忆落 sqlite（agent_rag.db），按 user_id 物理隔离，重启不丢；
  - 检索管线：向量 top-N 召回 → recency/importance/relevance 三维权值重排 → 阈值过滤 → 文本去重 → top-k；
  - 时间衰减：融合 Soulous 现有 `cos × 0.5^(ageDays/halfLife)` 半衰期公式作为 recency 项；
  - 降级语义：embedding 不可用时检索返回空集并打 WARN——绝不返回伪向量噪音（原 md5 降级已删除）。

向量计算优先用 sqlite-vec 的 vec_distance_cosine 标量函数（SQL 内完成）；
扩展加载失败（少数平台）时自动回退到 Python 余弦全扫——按用户隔离后单用户语料量级下足够。
"""
import logging
import math
import sqlite3
import struct
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from openai import OpenAI

logger = logging.getLogger("VectorService")

# 重排权重：relevance（语义相似度）为主，recency（半衰期衰减）次之，importance 兜底
W_RELEVANCE = 0.45
W_RECENCY = 0.30
W_IMPORTANCE = 0.25
HALF_LIFE_DAYS = 90.0
MIN_SIMILARITY = 0.30
CANDIDATE_POOL = 20


def _pack(vec: List[float]) -> bytes:
    return struct.pack(f"{len(vec)}f", *vec)


def _unpack(blob: bytes) -> List[float]:
    n = len(blob) // 4
    return list(struct.unpack(f"{n}f", blob))


def _cosine(a: List[float], b: List[float]) -> float:
    if len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return 0.0 if na == 0 or nb == 0 else dot / (na * nb)


class VectorService:
    def __init__(self, db_path: str = "data/agent_rag.db",
                 api_key: str = "", base_url: str = "",
                 model: str = "text-embedding-v4", dimension: int = 768):
        self.db_path = db_path
        self.dimension = dimension
        self.model = model
        self.client: Optional[OpenAI] = None
        self._lock = threading.Lock()
        self._vec_ext = False

        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._init_db()
        if api_key:
            try:
                self.client = OpenAI(api_key=api_key, base_url=base_url or None)
                logger.info("[RAG] Embedding 客户端就绪，模型: %s", self.model)
            except Exception as e:
                logger.warning("[RAG] Embedding 客户端初始化失败: %s，检索将返回空集。", e)
        else:
            logger.warning("[RAG] 未配置 EMBEDDING_API_KEY，检索将返回空集（不降级为伪向量）。")

    # ------------------------------------------------------------- infra --

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.execute("PRAGMA journal_mode=WAL")
        try:
            import sqlite_vec
            conn.enable_load_extension(True)
            sqlite_vec.load(conn)
            conn.enable_load_extension(False)
            self._vec_ext = True
        except Exception:
            self._vec_ext = False
        return conn

    def _init_db(self):
        with self._connect() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS memory (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    source_id INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    embedding BLOB NOT NULL,
                    dim INTEGER NOT NULL,
                    importance REAL DEFAULT 5.0,
                    access_count INTEGER DEFAULT 0,
                    last_accessed REAL,
                    created_at REAL,
                    updated_at REAL,
                    UNIQUE(user_id, source_type, source_id)
                )
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_memory_user ON memory(user_id)")
            conn.commit()

    # --------------------------------------------------------- embedding --

    def get_embedding(self, text: str) -> Optional[List[float]]:
        """生成向量；不可用或异常时返回 None（调用方按空结果处理）。"""
        if not self.client:
            return None
        try:
            resp = self.client.embeddings.create(
                input=text, model=self.model,
                extra_body={"dimensions": self.dimension},
            )
            vector = resp.data[0].embedding
            if len(vector) != self.dimension:
                logger.warning("[RAG] 返回维度 %d 与预设 %d 不符，本次按不可用处理。", len(vector), self.dimension)
                return None
            return vector
        except Exception as e:
            logger.warning("[RAG] Embedding 调用失败: %s", e)
            return None

    @property
    def available(self) -> bool:
        return self.client is not None

    # --------------------------------------------------------------- crud --

    def upsert(self, user_id: str, source_type: str, source_id: int,
               text: str, importance: float = 5.0) -> bool:
        """幂等 upsert：(user_id, source_type, source_id) 唯一；text 为空时删除该条。"""
        if not text or not text.strip():
            self.delete(user_id, source_type, source_id)
            return True
        vec = self.get_embedding(text.strip())
        if vec is None:
            return False
        now = time.time()
        with self._lock, self._connect() as conn:
            conn.execute("""
                INSERT INTO memory (user_id, source_type, source_id, text, embedding, dim,
                                    importance, access_count, last_accessed, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT(user_id, source_type, source_id) DO UPDATE SET
                    text = excluded.text,
                    embedding = excluded.embedding,
                    dim = excluded.dim,
                    importance = excluded.importance,
                    updated_at = excluded.updated_at
            """, (user_id, source_type, source_id, text.strip(), _pack(vec), len(vec),
                  importance, now, now, now))
            conn.commit()
        return True

    def delete(self, user_id: str, source_type: Optional[str] = None,
               source_id: Optional[int] = None) -> int:
        sql = "DELETE FROM memory WHERE user_id = ?"
        params: List[Any] = [user_id]
        if source_type:
            sql += " AND source_type = ?"
            params.append(source_type)
        if source_id is not None:
            sql += " AND source_id = ?"
            params.append(source_id)
        with self._lock, self._connect() as conn:
            cur = conn.execute(sql, params)
            conn.commit()
            return cur.rowcount

    # ------------------------------------------------------------- search --

    def search(self, user_id: str, query: str, k: int = 3,
               source_types: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        """
        检索管线：top-N 余弦召回 → 三维权值重排 → 阈值过滤 → 文本去重 → top-k。
        embedding 不可用 / 无语料时返回空列表。
        """
        if not query or not query.strip():
            return []
        query_vec = self.get_embedding(query.strip())
        if query_vec is None:
            return []

        candidates = self._recall(user_id, query_vec, source_types)
        if not candidates:
            return []

        now = time.time()
        scored = []
        for row in candidates:
            similarity = row["similarity"]
            if similarity < MIN_SIMILARITY:
                continue
            age_days = max(0.0, now - (row["updated_at"] or now)) / 86400.0
            recency = 0.5 ** (age_days / HALF_LIFE_DAYS)
            importance = min(max(row["importance"] / 10.0, 0.1), 1.0)
            weight = W_RELEVANCE * similarity + W_RECENCY * recency + W_IMPORTANCE * importance
            scored.append({**row, "weight": weight})

        scored.sort(key=lambda r: r["weight"], reverse=True)

        # 文本去重后取 top-k
        seen, out = set(), []
        for row in scored:
            key = row["text"][:80]
            if key in seen:
                continue
            seen.add(key)
            out.append(row)
            if len(out) >= k:
                break

        if out:
            self._touch([r["id"] for r in out])
        return [{
            "source_type": r["source_type"], "source_id": r["source_id"],
            "text": r["text"], "similarity": round(r["similarity"], 4),
            "weight": round(r["weight"], 4),
        } for r in out]

    def _recall(self, user_id: str, query_vec: List[float],
                source_types: Optional[List[str]]) -> List[Dict[str, Any]]:
        """向量召回候选池：优先 sqlite-vec SQL 内计算，回退 Python 余弦全扫。"""
        filters = "user_id = ? AND dim = ?"
        params: List[Any] = [user_id, len(query_vec)]
        if source_types:
            filters += f" AND source_type IN ({','.join('?' * len(source_types))})"
            params.extend(source_types)

        with self._lock, self._connect() as conn:
            conn.row_factory = sqlite3.Row
            if self._vec_ext:
                rows = conn.execute(
                    f"""SELECT id, source_type, source_id, text, importance, updated_at,
                               1.0 - vec_distance_cosine(embedding, ?) AS similarity
                        FROM memory WHERE {filters}
                        ORDER BY similarity DESC LIMIT {CANDIDATE_POOL}""",
                    [_pack(query_vec)] + params).fetchall()
                return [dict(r) for r in rows]
            # 回退：Python 余弦全扫（单用户语料 < 1w 条时毫秒级）
            rows = conn.execute(
                f"""SELECT id, source_type, source_id, text, embedding, importance, updated_at
                    FROM memory WHERE {filters}""", params).fetchall()
        scored = []
        for r in rows:
            sim = _cosine(query_vec, _unpack(r["embedding"]))
            scored.append({"id": r["id"], "source_type": r["source_type"],
                           "source_id": r["source_id"], "text": r["text"],
                           "importance": r["importance"], "updated_at": r["updated_at"],
                           "similarity": sim})
        scored.sort(key=lambda x: x["similarity"], reverse=True)
        return scored[:CANDIDATE_POOL]

    def _touch(self, ids: List[int]):
        """命中回写：access_count+1、刷新 last_accessed（越常被召回的记忆越不易遗忘）。"""
        now = time.time()
        try:
            with self._lock, self._connect() as conn:
                conn.executemany(
                    "UPDATE memory SET access_count = access_count + 1, last_accessed = ? WHERE id = ?",
                    [(now, i) for i in ids])
                conn.commit()
        except Exception as e:
            logger.warning("[RAG] 命中回写失败（忽略）: %s", e)
