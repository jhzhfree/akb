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
