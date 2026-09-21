"""Tests for the llmwiki-style answer presentation contract.

The console answer card renders a Markdown answer plus a badge row (question type,
rewrite, channels, rerank, confidence), supporting-claim chips, and a citation list.
These tests pin the helpers that produce those fields and the gate exemptions that
make Markdown answers compatible with the fail-closed citation gates.
"""

from app.services.answer_claims import split_claims
from app.services.query_engine import QueryEngine


def test_split_claims_exempts_markdown_structure():
    """Headings and table rule rows are navigation, so gates must not demand citations."""
    answer = (
        "## 部署步骤\n"
        "\n"
        "- 先安装 JDK [证据1]。\n"
        "- 再配置证书 [证据2]。\n"
        "\n"
        "| 项目 | 要求 |\n"
        "| --- | --- |\n"
        "\n"
        "**结论**：方案可行 [证据3]。"
    )
    claims = split_claims(answer)
    assert claims == ["先安装 JDK [证据1]。", "再配置证书 [证据2]。", "| 项目 | 要求 |", "结论：方案可行 [证据3]。"]


def test_claim_triple_parses_indexed_claim_rows():
    """Claim rows are indexed as "subject PREDICATE object" and split back into a triple."""
    assert QueryEngine._claim_triple("Tomcat 8.5 SUPPORTS PKCS12") == ("Tomcat 8.5", "SUPPORTS", "PKCS12")
    assert QueryEngine._claim_triple("Spring Boot REQUIRES JDK >= 8") == ("Spring Boot", "REQUIRES", "JDK >= 8")
    # Unparsable content degrades to a subject-only chip instead of crashing the answer.
    assert QueryEngine._claim_triple("没有大写谓词的句子") == ("没有大写谓词的句子", "", "")
    assert QueryEngine._claim_triple("") == ("", "", "")


def test_supporting_claims_projects_claim_candidates():
    """Only CLAIM-typed candidates become chips, deduplicated and capped at eight."""

    def claim(claim_id, content, status="EFFECTIVE"):
        return {
            "id": claim_id,
            "content": content,
            "metadata": {"object_type": "CLAIM", "status": status, "confidence": 0.9},
        }

    candidates = [
        claim("C1", "Tomcat 8.5 SUPPORTS PKCS12"),
        {"id": "K1", "content": "卡片内容", "metadata": {"object_type": "CARD"}},
        claim("C1", "Tomcat 8.5 SUPPORTS PKCS12"),
        claim("C2", "Nginx REQUIRES OpenSSL", status="CANDIDATE"),
    ]
    chips = QueryEngine._supporting_claims(candidates)
    assert [chip["claimId"] for chip in chips] == ["C1", "C2"]
    assert chips[0]["subject"] == "Tomcat 8.5"
    assert chips[0]["predicate"] == "SUPPORTS"
    assert chips[0]["object"] == "PKCS12"
    assert chips[1]["status"] == "CANDIDATE"


def test_confidence_follows_rerank_strength():
    """Rerank scores dominate; effective claims add a bonus; blocked answers are halved."""
    reranked = [{"rerankScore": 0.8}, {"rerankScore": 0.6}, {"rerankScore": 0.4}]
    assert QueryEngine._confidence(reranked, [], [], {"gateStatus": "PASS"}) == 0.6
    assert QueryEngine._confidence(reranked, [{"index": 1}], [{"status": "EFFECTIVE"}], {"gateStatus": "PASS"}) == 0.7
    assert QueryEngine._confidence(reranked, [], [], {"gateStatus": "BLOCKED"}) == 0.3


def test_confidence_falls_back_to_citation_coverage():
    """Without rerank scores, citation coverage drives a capped base estimate."""
    assert QueryEngine._confidence([], [{"index": 1}, {"index": 2}], [], {"gateStatus": "PASS"}) == 0.35
    many = [{"index": index} for index in range(20)]
    assert QueryEngine._confidence([], many, [], {"gateStatus": "PASS"}) == 0.6
    assert QueryEngine._confidence([], [], [], {"gateStatus": "PASS"}) == 0.25
