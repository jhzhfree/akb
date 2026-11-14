"""Offline path-disclosure regressions against real KB-partitioned local stores."""

import pytest

from app.services.retrieval import hybrid
from app.storage import graph_store as graph_storage
from app.storage import index_store as index_storage


@pytest.fixture
def local_graph(tmp_path, monkeypatch):
    monkeypatch.setattr(graph_storage, "INDEX_DIR", tmp_path)
    monkeypatch.setattr(index_storage, "INDEX_DIR", tmp_path)
    graph = graph_storage.GraphStore()
    index = index_storage.IndexStore()
    monkeypatch.setattr(hybrid, "graph_store", graph)
    monkeypatch.setattr(hybrid, "store", index)
    graph.upsert(7, [
        {"code": code, "name": name, "permissionTags": tags}
        for code, name, tags in [
            ("start", "Public Start", []), ("end", "Public End", []),
            ("public", "Public Middle", []),
            ("a", "Private Alpha", ["A"]), ("b", "Private Bravo", ["B"]),
        ]
    ], [
        {"source": source, "target": target, "permissionTags": []}
        for middle in ("public", "a", "b")
        for source, target in (("start", middle), (middle, "end"))
    ])
    index.save(7, [{
        "id": "evidence", "knowledge_base_id": 7, "status": "APPROVED",
        "title": "Public endpoint evidence", "content": "Public Start and Public End",
        "permission_tags": [], "evidence_code": "E1", "evidence_hash": "hash",
    }])
    return object.__new__(hybrid.HybridRetriever), graph, index


def paths(rows):
    return {tuple([row["metadata"]["graph_path"][0]["sourceCode"]] + [
        leg["targetCode"] for leg in row["metadata"]["graph_path"]
    ]) for row in rows}


@pytest.mark.parametrize("tags,middles", [
    ([], {"public"}), (None, {"public"}),
    (["A"], {"public", "a"}), (["B"], {"public", "b"}),
    (["A", "B"], {"public", "a", "b"}),
])
def test_public_evidence_cannot_reveal_private_intermediate(local_graph, tags, middles):
    retriever, _, _ = local_graph
    rows = retriever._graph_local(7, "Public Start", 20, tags)
    assert paths(rows) == {("start", middle, "end") for middle in middles}
    # Verify names/content as well as structured path IDs, including the legacy None contract.
    rendered = str(rows)
    if "a" not in middles:
        assert "Private Alpha" not in rendered
    if "b" not in middles:
        assert "Private Bravo" not in rendered


def test_private_seed_is_not_disclosed(local_graph):
    retriever, _, index = local_graph
    docs = index.load(7)
    docs[0]["content"] += " Private Alpha"
    index.save(7, docs)
    assert retriever._graph_local(7, "Private Alpha", 20, []) == []
    assert retriever._graph_local(7, "Private Alpha", 20, None) == []
    assert retriever._graph_local(7, "Private Alpha", 20, ["A"])


@pytest.mark.parametrize("component", ["node", "edge", "document"])
def test_explicit_foreign_kb_rows_cannot_support_path(local_graph, component):
    retriever, graph, index = local_graph
    payload = graph.load(7)
    if component == "node":
        payload["entities"]["public"]["knowledgeBaseId"] = 8
    elif component == "edge":
        for edge in payload["relations"]:
            edge["knowledge_base_id"] = 8
    else:
        docs = index.load(7)
        docs[0]["knowledge_base_id"] = 8
        index.save(7, docs)
    graph._write(7, payload)
    assert retriever._graph_local(7, "Public Start", 20, []) == []
    assert retriever._graph_local(8, "Public Start", 20, ["A", "B"]) == []


def test_restricted_edges_and_support_still_enforced(local_graph):
    retriever, graph, index = local_graph
    payload = graph.load(7)
    for edge in payload["relations"]:
        edge["permissionTags"] = ["A"]
    graph._write(7, payload)
    assert retriever._graph_local(7, "Public Start", 20, []) == []
    assert paths(retriever._graph_local(7, "Public Start", 20, ["A"])) == {
        ("start", "public", "end"), ("start", "a", "end")
    }
    docs = index.load(7)
    docs[0]["permission_tags"] = ["B"]
    index.save(7, docs)
    assert retriever._graph_local(7, "Public Start", 20, ["A"]) == []
