"""`_context` is the source of truth for "what the answer actually saw".

`selectedContext`, `citations` and the `[证据N]` blocks in `context` are three views of the
same thing, and the answer gates judge the answer against them. If they drift apart — for
example `selectedContext` reporting fewer rows than there are evidence blocks — the gates
judge claims against an evidence set the answer never had, and sourced sentences get
redacted as if they were unsupported.

These tests pin that alignment so a silent edit to `_context` cannot break it again.
"""

import re

from app.services.query_engine import QueryEngine


def _candidate(cid, content):
    return {
        "id": cid,
        "title": "标题%s" % cid,
        "channel": "bm25",
        "content": content,
        "metadata": {"object_type": "CONTENT_UNIT", "source_name": "doc.md"},
    }


def _blocks(text):
    return re.findall(r"\[证据\d+\]", text)


def test_selected_matches_citations_and_context_blocks():
    candidates = [_candidate(str(i), "年假额度为司龄满 1 年 5 天。" * 3) for i in range(1, 6)]
    context, citations, selected = QueryEngine()._context(candidates, limit=100000)

    assert [c["id"] for c in selected] == [c["id"] for c in citations]
    assert len(_blocks(context)) == len(citations) == len(selected) == 5


def test_selected_stops_where_the_char_budget_stops():
    """A candidate cut off by the budget is not evidence, so it must not be in `selected`."""
    candidates = [_candidate(str(i), "年假额度为司龄满 1 年 5 天。") for i in range(1, 6)]
    # 每条 block 约 34 字符，所以这个预算装得下前几条但装不下全部。
    context, citations, selected = QueryEngine()._context(candidates, limit=80)

    assert len(selected) == len(citations) == len(_blocks(context))
    # 预算必须真的截断过，否则这条测试等于没测。
    assert 0 < len(selected) < len(candidates)


def test_every_selected_candidate_appears_in_the_context():
    candidates = [_candidate(str(i), "制度修订通知：年假调整为 10 天。" * 5) for i in range(1, 4)]
    context, citations, selected = QueryEngine()._context(candidates, limit=100000)

    for candidate in selected:
        assert candidate["content"] in context
