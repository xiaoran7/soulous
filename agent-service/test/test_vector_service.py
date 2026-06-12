import time


def test_upsert_and_search(vector_service):
    vector_service.upsert("u1", "COMPLETED_TASK", 1, "学完了数据结构链表一章")
    vector_service.upsert("u1", "DAILY_REVIEW", 2, "今天复盘了英语口语练习")

    hits = vector_service.search("u1", "链表数据结构", k=3)
    assert hits
    assert hits[0]["text"] == "学完了数据结构链表一章"
    assert hits[0]["similarity"] > 0.5


def test_user_isolation(vector_service):
    vector_service.upsert("u1", "COMPLETED_TASK", 1, "数据结构链表")
    assert vector_service.search("u2", "数据结构链表", k=3) == []


def test_upsert_idempotent(vector_service):
    vector_service.upsert("u1", "COMPLETED_TASK", 1, "数据结构链表")
    vector_service.upsert("u1", "COMPLETED_TASK", 1, "数学算法练习")
    hits = vector_service.search("u1", "数学算法", k=5)
    assert len(hits) == 1
    assert hits[0]["text"] == "数学算法练习"


def test_blank_text_deletes(vector_service):
    vector_service.upsert("u1", "COMPLETED_TASK", 1, "数据结构链表")
    vector_service.upsert("u1", "COMPLETED_TASK", 1, "  ")
    assert vector_service.search("u1", "数据结构链表", k=3) == []


def test_source_type_filter(vector_service):
    vector_service.upsert("u1", "EPISODE", 1, "数据结构学习计划确认")
    vector_service.upsert("u1", "COMPLETED_TASK", 2, "数据结构链表完成")
    hits = vector_service.search("u1", "数据结构", k=5, source_types=["EPISODE"])
    assert len(hits) == 1
    assert hits[0]["source_type"] == "EPISODE"


def test_importance_breaks_tie(vector_service):
    vector_service.upsert("u1", "EPISODE", 1, "数据结构链表", importance=1.0)
    vector_service.upsert("u1", "EPISODE", 2, "数据结构链表 ", importance=9.5)
    hits = vector_service.search("u1", "数据结构链表", k=2)
    assert hits[0]["source_id"] == 2  # 同相似度下高重要度优先


def test_recency_decay(vector_service):
    vector_service.upsert("u1", "EPISODE", 1, "数据结构链表")
    vector_service.upsert("u1", "EPISODE", 2, "数据结构链表 ")
    # 手动把 1 号的 updated_at 拨回一年前
    with vector_service._connect() as conn:
        conn.execute("UPDATE memory SET updated_at = ? WHERE source_id = 1",
                     (time.time() - 365 * 86400,))
        conn.commit()
    hits = vector_service.search("u1", "数据结构链表", k=2)
    assert hits[0]["source_id"] == 2  # 新记忆权值更高


def test_unavailable_returns_empty(vector_service):
    vector_service.upsert("u1", "EPISODE", 1, "数据结构链表")
    vector_service._fake_available = False
    assert vector_service.search("u1", "数据结构链表", k=3) == []


def test_delete_scopes(vector_service):
    vector_service.upsert("u1", "EPISODE", 1, "数据结构")
    vector_service.upsert("u1", "COMPLETED_TASK", 2, "链表")
    assert vector_service.delete("u1", "EPISODE") == 1
    assert vector_service.delete("u1") == 1
