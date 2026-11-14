from app.services.graph_provider import Neo4jGraphProvider


def test_permission_tags_normalization():
    assert Neo4jGraphProvider._permission_tags({"permission_tags": '["team-a","ops"]'}) == ["team-a", "ops"]
    assert Neo4jGraphProvider._permission_tags({"permission_tags": []}) == []


def test_graph_search_uses_pre_topk_acl_without_driver():
    provider = object.__new__(Neo4jGraphProvider)
    captured = {}

    def execute(cypher, params):
        captured["cypher"] = cypher
        captured["params"] = params
        return [], None

    provider._execute = execute
    assert provider.search(7, "tls", 10, ["team-a"]) == []
    assert "permissionTags" in captured["cypher"]
    assert "any(tag IN coalesce(n.permissionTags, []) WHERE tag IN $permissionTags)" in captured["cypher"]
    assert captured["params"]["permissionTags"] == ["team-a"]


class _Counters:
    def __init__(self, nodes=0, relationships=0):
        self.nodes_created = nodes
        self.relationships_created = relationships


class _Summary:
    def __init__(self, counters):
        self.counters = counters


def _stub_provider(captured):
    """A Neo4jGraphProvider whose driver is replaced by a capture-only _execute."""
    provider = object.__new__(Neo4jGraphProvider)

    def execute(cypher, params):
        captured.append({"cypher": cypher, "params": params})
        return [], _Summary(_Counters())

    provider._execute = execute
    return provider


def test_upsert_relation_prefers_entity_code_over_name():
    """Relations stamped with resolved codes must connect indexed entity nodes."""
    captured = []
    provider = _stub_provider(captured)
    provider._upsert_relation(
        7,
        {
            "source": "网关服务",
            "target": "注册中心",
            "source_entity_code": "ENT_source_1",
            "target_entity_code": "ENT_target_1",
            "relation_type": "DEPENDS_ON",
            "confidence": 0.8,
        },
    )
    params = captured[0]["params"]
    assert params["source"] == "ENT_source_1"
    assert params["target"] == "ENT_target_1"
    # Names stay readable for ON CREATE fallback when the node does not exist yet.
    assert params["sourceName"] == "网关服务"
    assert params["targetName"] == "注册中心"


def test_upsert_relation_falls_back_to_names_without_codes():
    captured = []
    provider = _stub_provider(captured)
    provider._upsert_relation(7, {"source": "网关服务", "target": "注册中心", "relation_type": "USES"})
    params = captured[0]["params"]
    assert params["source"] == "网关服务"
    assert params["target"] == "注册中心"


def test_upsert_claim_links_batch_entity_code():
    """Claim ABOUT edges must reuse the entity indexed from the same batch."""
    captured = []
    provider = _stub_provider(captured)
    index = Neo4jGraphProvider._entity_code_index(
        [
            {"entity_code": "ENT_1", "name": "网关 服务"},
        ]
    )
    assert index == {"网关服务": "ENT_1"}
    provider._upsert_claim(7, {"claim_code": "CLM_1", "subject": "网关服务"}, index)
    assert captured[1]["params"]["entityCode"] == "ENT_1"


def test_upsert_claim_falls_back_to_auto_entity():
    captured = []
    provider = _stub_provider(captured)
    provider._upsert_claim(7, {"claim_code": "CLM_1", "subject": "未知主题"}, {})
    assert captured[1]["params"]["entityCode"] == "AUTO_未知主题"


def test_neighborhood_all_path_nodes_are_kb_and_acl_scoped():
    # An allowed endpoint pair is insufficient: every intermediate node must
    # pass ACL and KB checks before UNWIND can expose its title.
    for tags in ([], ["team-a"]):
        captured = []
        provider = _stub_provider(captured)
        result = provider.neighborhood(7, "public-start", depth=3, permission_tags=tags)
        assert result["nodes"] == []
        query = captured[0]["cypher"]
        assert "all(node IN nodes(p) WHERE node.knowledgeBaseId=$kb AND " in query
        assert "size(coalesce(node.permissionTags, []))=0 OR any(tag IN coalesce(node.permissionTags, []) WHERE tag IN $permissionTags)" in query
        assert "all(rel IN relationships(p)" in query
        assert query.index("all(node IN nodes(p)") < query.index("WITH p LIMIT")
        assert captured[0]["params"]["permissionTags"] == tags


def test_generation_neighborhood_all_path_nodes_are_generation_kb_and_acl_scoped():
    for tags in ([], ["team-a"]):
        captured = []
        provider = _stub_provider(captured)
        provider.active_generation = lambda kb: "active"
        result = provider.generation_neighborhood(7, "public-start", depth=3, permission_tags=tags)
        assert result["paths"] == []
        query = captured[0]["cypher"]
        assert "all(node IN nodes(p) WHERE node.knowledgeBaseId=$kb AND node.generationId=ptr.activeGenerationId AND " in query
        assert "size(coalesce(node.permissionTags,[]))=0 OR any(t IN coalesce(node.permissionTags,[]) WHERE t IN $tags)" in query
        assert "all(rel IN relationships(p)" in query
        assert query.index("all(node IN nodes(p)") < query.index("RETURN p LIMIT")
        assert captured[0]["params"]["tags"] == tags


def test_generation_multihop_scopes_every_node_and_evidence_object():
    for tags in ([], ["team-a"]):
        captured = []
        provider = _stub_provider(captured)
        provider.active_generation = lambda kb: "active"
        result = provider.generation_multihop(7, "public-start", depth=3, permission_tags=tags)
        assert result["paths"] == []
        query = captured[0]["cypher"]
        assert "all(n IN nodes(p) WHERE n.knowledgeBaseId=$kb AND n.generationId=ptr.activeGenerationId" in query
        assert "size(coalesce(n.permissionTags,[]))=0 OR any(tag IN coalesce(n.permissionTags,[]) WHERE tag IN $tags)" in query
        assert "size(coalesce(o.permissionTags,[]))=0 OR any(tag IN coalesce(o.permissionTags,[]) WHERE tag IN $tags)" in query
        assert query.index("coalesce(o.permissionTags") < query.index("RETURN [n")
        assert "coalesce(about.permissionTags" in query
        assert captured[0]["params"]["tags"] == tags


def test_facet_multihop_checks_all_nodes_and_edges_before_limit():
    for tags in ([], ["team-a"]):
        captured = []
        provider = _stub_provider(captured)
        provider.active_generation = lambda kb: "active"
        assert provider.facet_multihop(7, "start", permission_tags=tags)["paths"] == []
        query = captured[0]["cypher"]
        assert "all(n IN nodes(p) WHERE n.knowledgeBaseId=$kb AND n.generationId=ptr.activeGenerationId" in query
        assert "coalesce(n.permissionTags,[])" in query
        assert "all(r IN relationships(p) WHERE r.generationId=ptr.activeGenerationId" in query
        assert "coalesce(r.permissionTags,[])" in query
        assert query.index("all(n IN nodes(p)") < query.index("RETURN DISTINCT")
        assert captured[0]["params"]["tags"] == tags


def test_citation_multihop_scopes_all_entities_evidence_sources_and_links():
    for tags in ([], ["team-a"]):
        captured = []
        provider = _stub_provider(captured)
        provider.active_generation = lambda kb: "active"
        assert provider.citation_safe_multihop(7, "start", permission_tags=tags)["paths"] == []
        query = captured[0]["cypher"]
        for var, label in (("s", "EntityGen"), ("t", "EntityGen"), ("rf", "RelationFacetGen"), ("ev", "EvidenceGen"), ("src", "SourceGen")):
            assert "(%s:%s {knowledgeBaseId:$kb,generationId:ptr.activeGenerationId" % (var, label) in query
        for var in ("rf", "ev", "src"):
            assert "coalesce(%s.permissionTags,[])" % var in query
        assert "all(n IN [s,t] WHERE size(coalesce(n.permissionTags,[]))=0" in query
        assert "all(r IN [startLink,support,derived,targetLink] WHERE" in query
        assert captured[0]["params"]["tags"] == tags
