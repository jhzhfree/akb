import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.core.config import settings
from app.core.contracts import (
    DEFAULT_RETRIEVABLE_STATUSES,
    STANDARD_RETRIEVABLE_STATUSES,
    resolve_retrievable_statuses,
)
from app.services.conflict_detector import ConflictDetector
from app.services.embedding import get_embedding_provider
from app.services.graph_provider import Neo4jGraphProvider, get_graph_provider
from app.services.reranker import get_reranker
from app.services.retrieval.elasticsearch_provider import _status_filter
from app.services.retrieval.hybrid import allowed
from app.services.retrieval.milvus_provider import MilvusProvider
from app.storage.index_store import IndexStore


def test_heavy_providers_are_singletons():
    assert get_embedding_provider() is get_embedding_provider()
    assert get_reranker() is get_reranker()
    assert get_graph_provider() is get_graph_provider()


def test_index_store_snapshot_cache():
    with tempfile.TemporaryDirectory() as directory:
        store = IndexStore()
        store._path = lambda kb: Path(directory) / ("kb_%s.json" % kb)
        docs = [{"id": "1", "content": "first"}]
        store.save(1, docs)
        first = store.load(1)
        second = store.load(1)
        assert first is second, "unchanged index should reuse the in-process snapshot"
        store.upsert(1, [{"id": "2", "content": "second"}])
        third = store.load(1)
        assert third is not first
        assert len(third) == 2


def test_retrieval_status_filter():
    assert allowed({"id": "chunk", "permission_tags": []}, [])
    assert allowed({"id": "card", "status": "APPROVED", "permission_tags": []}, [])
    assert allowed({"id": "card", "status": "EFFECTIVE", "permission_tags": []}, [])
    assert allowed({"id": "card", "status": "DRAFT", "permission_tags": []}, [])
    assert allowed({"id": "claim", "status": "UNCERTAIN", "permission_tags": []}, [])
    for status in ("PENDING_REVIEW", "CONFLICTED", "REJECTED"):
        assert not allowed({"id": "candidate", "status": status, "permission_tags": []}, [])


def test_retrieval_status_policy_configuration():
    assert resolve_retrievable_statuses("permissive") == DEFAULT_RETRIEVABLE_STATUSES
    assert resolve_retrievable_statuses("standard") == STANDARD_RETRIEVABLE_STATUSES
    assert resolve_retrievable_statuses("custom", "approved, DRAFT") == {"APPROVED", "DRAFT"}
    for policy, allowed_statuses in (("unknown", ""), ("custom", ""), ("custom", "INVALID_STATE")):
        try:
            resolve_retrievable_statuses(policy, allowed_statuses)
        except ValueError:
            continue
        raise AssertionError("invalid retrieval status policy must fail closed")

    original_policy = settings.retrieval_status_policy
    original_allowed_statuses = settings.retrieval_allowed_statuses
    try:
        settings.retrieval_status_policy = "permissive"
        assert allowed({"id": "legacy", "permission_tags": []}, [])
        assert len(_status_filter()["bool"]["should"]) == 2
        milvus = object.__new__(MilvusProvider)
        assert 'status == ""' in milvus._filter_expr(1, [], None)
        graph = object.__new__(Neo4jGraphProvider)
        graph_queries = []
        graph._execute = lambda query, params: (graph_queries.append(query) or [], True)
        graph.search(1, "test")
        assert "n.status IS NULL" in graph_queries[0]

        settings.retrieval_status_policy = "standard"
        assert not allowed({"id": "draft", "status": "DRAFT", "permission_tags": []}, [])
        assert not allowed({"id": "legacy", "permission_tags": []}, [])
        assert allowed({"id": "approved", "status": "APPROVED", "permission_tags": []}, [])
        es_statuses = _status_filter()["bool"]["should"][0]["terms"]["status"]
        assert es_statuses == ["APPROVED", "EFFECTIVE"]
        assert len(_status_filter()["bool"]["should"]) == 1
        milvus_filter = milvus._filter_expr(1, [], None)
        assert 'status in ["APPROVED", "EFFECTIVE"]' in milvus_filter
        assert 'status == ""' not in milvus_filter
        graph_queries = []
        graph._execute = lambda query, params: (graph_queries.append(query) or [], True)
        graph.search(1, "test")
        assert "n.status IN ['APPROVED', 'EFFECTIVE']" in graph_queries[0]
        assert "n.status IS NULL" not in graph_queries[0]
        settings.retrieval_status_policy = "custom"
        settings.retrieval_allowed_statuses = "DRAFT"
        assert allowed({"id": "draft", "status": "DRAFT", "permission_tags": []}, [])
        assert not allowed({"id": "approved", "status": "APPROVED", "permission_tags": []}, [])
        assert not allowed({"id": "legacy", "permission_tags": []}, [])
    finally:
        settings.retrieval_status_policy = original_policy
        settings.retrieval_allowed_statuses = original_allowed_statuses


def test_conflict_detector_focus_and_grouping():
    claims = []
    for index in range(200):
        claims.append(
            {
                "claim_code": "UNRELATED_%s" % index,
                "subject": "subject-%s" % index,
                "predicate": "IS",
                "object": "value",
            }
        )
    claims.extend(
        [
            {"claim_code": "C_NEW", "subject": "Tomcat", "predicate": "SUPPORTS", "object": "支持 PKCS12"},
            {"claim_code": "C_OLD", "subject": "Tomcat", "predicate": "SUPPORTS", "object": "不支持 PKCS12"},
            {"claim_code": "C_OTHER", "subject": "Tomcat", "predicate": "SUPPORTS", "object": "支持 JKS"},
        ]
    )
    rows = ConflictDetector().detect(claims, focus_codes=["C_NEW"])
    assert rows
    assert all("C_NEW" in {row["objectACode"], row["objectBCode"]} for row in rows)


if __name__ == "__main__":
    test_heavy_providers_are_singletons()
    test_index_store_snapshot_cache()
    test_conflict_detector_focus_and_grouping()
    test_retrieval_status_filter()
    test_retrieval_status_policy_configuration()
    print("performance regression checks passed")
