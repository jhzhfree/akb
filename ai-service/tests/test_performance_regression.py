import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.services.conflict_detector import ConflictDetector
from app.services.embedding import get_embedding_provider
from app.services.graph_provider import get_graph_provider
from app.services.reranker import get_reranker
from app.services.retrieval.hybrid import allowed
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
    assert allowed({"id":"chunk","permission_tags":[]}, [])
    assert allowed({"id":"card","status":"APPROVED","permission_tags":[]}, [])
    assert allowed({"id":"card","status":"EFFECTIVE","permission_tags":[]}, [])
    assert not allowed({"id":"card","status":"DRAFT","permission_tags":[]}, [])
    assert not allowed({"id":"claim","status":"CONFLICTED","permission_tags":[]}, [])


def test_conflict_detector_focus_and_grouping():
    claims = []
    for index in range(200):
        claims.append({
            "claim_code": "UNRELATED_%s" % index,
            "subject": "subject-%s" % index,
            "predicate": "IS",
            "object": "value",
        })
    claims.extend([
        {"claim_code": "C_NEW", "subject": "Tomcat", "predicate": "SUPPORTS", "object": "支持 PKCS12"},
        {"claim_code": "C_OLD", "subject": "Tomcat", "predicate": "SUPPORTS", "object": "不支持 PKCS12"},
        {"claim_code": "C_OTHER", "subject": "Tomcat", "predicate": "SUPPORTS", "object": "支持 JKS"},
    ])
    rows = ConflictDetector().detect(claims, focus_codes=["C_NEW"])
    assert rows
    assert all("C_NEW" in {row["objectACode"], row["objectBCode"]} for row in rows)


if __name__ == "__main__":
    test_heavy_providers_are_singletons()
    test_index_store_snapshot_cache()
    test_conflict_detector_focus_and_grouping()
    test_retrieval_status_filter()
    print("performance regression checks passed")
