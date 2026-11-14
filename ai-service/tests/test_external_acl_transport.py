"""Offline ACL transport/filter contracts, not a live multi-IdP integration test."""
import json

from app.models.schemas import QueryRequest
from app.services.retrieval.elasticsearch_provider import _permission_filter
from app.services.retrieval.hybrid import allowed
from app.services.retrieval.milvus_provider import MilvusProvider


def request(tags):
    return QueryRequest.model_validate_json(json.dumps({
        "knowledgeBaseId": 1, "query": "q", "permissionTags": tags,
    }))


def test_json_preserves_null_empty_and_nonempty():
    for tags in (None, [], ["a"]):
        parsed = QueryRequest.model_validate_json(request(tags).model_dump_json())
        assert parsed.permissionTags == tags


def test_fulltext_filter_distinguishes_public_only_from_unconstrained():
    assert _permission_filter(None) is None
    public = _permission_filter(request([]).permissionTags)
    assert len(public["bool"]["should"]) == 1
    scoped = _permission_filter(request(["a"]).permissionTags)
    assert {"terms": {"permission_tags": ["a"]}} in scoped["bool"]["should"]
    assert {"terms": {"permission_tags": ["b"]}} not in scoped["bool"]["should"]


def test_vector_filter_distinguishes_public_only_from_unconstrained():
    provider = object.__new__(MilvusProvider)  # no client, network or model
    unrestricted = provider._filter_expr(1, None, "CARD")
    public = provider._filter_expr(1, request([]).permissionTags, "CARD")
    scoped = provider._filter_expr(1, request(["a"]).permissionTags, "CARD")
    assert "permission_tags" not in unrestricted
    assert 'json_contains_any(permission_tags, ["__public__"])' in public
    assert '"a"' in scoped and '"b"' not in scoped


def test_same_kb_chunk_acl_and_revocation_reuses_documents_not_authorization():
    documents = [
        {"id": "public", "status": "APPROVED", "permission_tags": []},
        {"id": "a", "status": "APPROVED", "permission_tags": ["a"]},
        {"id": "b", "status": "APPROVED", "permission_tags": ["b"]},
    ]
    def visible(tags):
        return [doc["id"] for doc in documents if allowed(doc, request(tags).permissionTags)]
    assert visible(None) == ["public", "a", "b"]
    assert visible(["a"]) == ["public", "a"]
    assert visible(["b"]) == ["public", "b"]
    assert visible([]) == ["public"]  # A revoked; same cached documents
    assert visible(["b"]) == ["public", "b"]
