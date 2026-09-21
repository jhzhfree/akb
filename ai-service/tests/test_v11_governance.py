import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.services.conflict_detector import ConflictDetector
from app.services.graph_provider import get_graph_provider
from app.services.reranker import LexicalCrossFeatureReranker


def test_conflict_detector():
    claims = [
        {"claim_code": "C1", "subject": "Tomcat 8.5", "predicate": "SUPPORTS", "object": "支持 PKCS12"},
        {"claim_code": "C2", "subject": "Tomcat 8.5", "predicate": "SUPPORTS", "object": "不支持 PKCS12"},
        {"claim_code": "C3", "subject": "Tomcat 9", "predicate": "SUPPORTS", "object": "支持 PKCS12"},
    ]
    rows = ConflictDetector().detect(claims)
    assert len(rows) == 1
    assert rows[0]["conflictType"] == "POLARITY_CONFLICT"
    assert rows[0]["objectACode"] == "C1"


def test_reranker():
    candidates = [
        {
            "id": "1",
            "channel": "card",
            "title": "证书部署",
            "content": "Tomcat PKCS12 证书部署步骤",
            "score": 0.02,
            "metadata": {},
        },
        {
            "id": "2",
            "channel": "card",
            "title": "项目管理",
            "content": "项目范围管理与需求变更",
            "score": 0.03,
            "metadata": {},
        },
    ]
    result = LexicalCrossFeatureReranker().rerank("Tomcat PKCS12 如何部署", candidates, 2)
    assert result[0]["id"] == "1"
    assert "rerankScore" in result[0]


def test_graph_provider_fallback():
    provider = get_graph_provider()
    # Neo4j 未启动时必须安全回退，不应阻断 AI 服务启动。
    assert provider is not None


if __name__ == "__main__":
    test_conflict_detector()
    test_reranker()
    test_graph_provider_fallback()
    print("governance smoke passed")
