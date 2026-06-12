from app.core.loop import envelopes


PLAN_TEXT = (
    "给你拆好了：\n<PLAN_JSON>\n"
    '{"category":"数据结构","tasks":[{"title":"学链表","description":"看教材","estimatedMinutes":30,'
    '"difficulty":"NORMAL","taskType":"STUDY"}]}\n</PLAN_JSON>'
)


def test_parse_plan_ok():
    plan = envelopes.parse_plan(PLAN_TEXT)
    assert plan is not None
    assert plan.category == "数据结构"
    assert plan.tasks[0].title == "学链表"
    assert plan.tasks[0].estimated_minutes == 30


def test_parse_plan_rejects_empty_tasks():
    text = '<PLAN_JSON>{"category":"x","tasks":[{"title":"  "}]}</PLAN_JSON>'
    assert envelopes.parse_plan(text) is None


def test_parse_plan_missing_envelope():
    assert envelopes.parse_plan("我已经为你生成计划草案了，如下") is None


def test_parse_clarify_ok():
    text = ('<CLARIFY_JSON>{"questions":[{"id":"q1","question":"用什么语言？",'
            '"multiSelect":false,"options":[{"label":"Python"},{"label":"Java"}]}]}</CLARIFY_JSON>')
    clarify = envelopes.parse_clarify(text)
    assert clarify is not None
    assert clarify.questions[0].options[0].label == "Python"


def test_plan_claim_heuristic():
    assert envelopes.looks_like_plan_claim("我已经为你生成了学习计划草案，如下")
    assert not envelopes.looks_like_plan_claim("链表是一种线性数据结构。")


def test_strip_json_fence():
    assert envelopes.strip_json_fence('```json\n{"a":1}\n```') == '{"a":1}'
