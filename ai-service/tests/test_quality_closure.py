"""Regression cases for source-grounded extraction, negative rules, comparison scope and evidence presentation."""
import pytest
from app.core.query_policy import QueryPolicy
from app.services.conflict_detector import ConflictDetector
from app.services.extractor import KnowledgeExtractor
from app.services.query_engine import QueryEngine


def material(text):
    return [{"unit_id": "U1", "evidence_code": "EV1", "heading_path": "制度/说明", "content": text}]


def test_plain_prose_does_not_fabricate_a_claim():
    result = KnowledgeExtractor()._fallback("", material("这是一段描述企业文化和历史背景的普通文字。"))
    assert result["cards"]
    assert result["claims"] == []


@pytest.mark.parametrize("text,polarity", [("员工不应共享账号。", "NEGATIVE"), ("员工不得共享账号。", "NEGATIVE"),
                                           ("员工必须提前提交申请。", "POSITIVE")])
def test_rule_claims_keep_subject_and_negation_without_auto_approval(text, polarity):
    claims = KnowledgeExtractor()._fallback("", material(text))["claims"]
    assert len(claims) == 1
    assert claims[0]["subject"] == "员工"
    assert claims[0]["polarity"] == polarity
    assert claims[0]["derivation"] == "RULE_FALLBACK"
    assert claims[0]["evidence_ids"] == ["EV1"]


@pytest.mark.parametrize("text", ["如果发生故障，员工应提交报告。", "员工不得不共享账号。", "员工是否负责安全检查？"])
def test_conditions_double_negatives_and_questions_are_not_flattened(text):
    assert KnowledgeExtractor()._fallback("", material(text))["claims"] == []


def test_model_prompt_exposes_real_evidence_ids():
    prompt = KnowledgeExtractor._prompt("员工负责提交报告。", material("员工负责提交报告。"))
    assert "[evidence_code=EV1]" in prompt
    assert "主语与宾语不得相同" in prompt


def claim(code, value, **extra):
    return {"claim_code": code, "subject": "平台", "predicate": "SUPPORTS", "object": value, **extra}


def test_multivalued_claims_and_disjoint_versions_are_not_conflicts():
    detector = ConflictDetector()
    assert not detector.detect([claim("A", "MySQL"), claim("B", "PostgreSQL")])
    assert not detector.detect([claim("A", "支持MySQL", valid_to="2024-01-01"),
                                claim("B", "不支持MySQL", valid_from="2025-01-01")])
    assert not detector.detect([claim("A", "MySQL", polarity="POSITIVE", conditions=["财务部"]),
                                claim("B", "MySQL", polarity="NEGATIVE", conditions=["研发部"])])
    rows = detector.detect([claim("A", "MySQL", polarity="POSITIVE"), claim("B", "MySQL", polarity="NEGATIVE")])
    assert len(rows) == 1 and rows[0]["conflictType"] == "POLARITY_CONFLICT"


def candidate(code, channel, text="年假为五天。", evidence="EV1", version=1):
    return {"id": code, "channel": channel, "title": "制度", "content": text, "score": 0.8,
            "metadata": {"evidence_code": evidence, "source_name": "制度", "source_uri": "test://policy",
                         "source_version": version, "heading_path": "人事/年假", "object_type": "CARD"}}


def test_same_evidence_and_same_content_merge_channels_and_keep_numbering():
    policy = QueryPolicy()
    policy.answer.minChunkEvidence = 0
    text, citations, selected = QueryEngine()._context([candidate("A", "card"), candidate("B", "bm25"),
                                                       candidate("C", "card", text="报销上限100元。")], policy=policy)
    assert len(citations) == len(selected) == 2
    assert citations[0]["channels"] == ["bm25", "card"]
    assert len(citations[0]["objectRefs"]) == 2
    assert "[证据1]" in text and "[证据2]" in text and "[证据3]" not in text
    assert citations[0]["title"].startswith("人事/年假")


def test_same_text_from_distinct_versions_preserves_provenance():
    _, citations, _ = QueryEngine()._context([candidate("A", "card"), candidate("B", "card", version=2)])
    assert len(citations) == 2


def test_context_uses_the_query_policy_snapshot(monkeypatch):
    policy = QueryPolicy()
    monkeypatch.setattr("app.services.query_engine.load_policy", lambda: pytest.fail("global policy was reloaded"))
    assert QueryEngine()._context([candidate("A", "card")], policy=policy)[1]
