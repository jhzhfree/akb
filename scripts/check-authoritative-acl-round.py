from pathlib import Path

R = Path(__file__).resolve().parents[1]
v10 = (R / "backend/src/main/resources/db/migration/V10__authoritative_acl.sql").read_text()
mapper = (R / "backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text()
controller = (R / "backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeController.java").read_text()
resolver = (R / "backend/src/main/java/com/jh/knowledgeos/service/AclContextResolver.java").read_text()
extract = (R / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java").read_text()
indexer = (R / "ai-service/app/services/indexer.py").read_text()
graph = (R / "ai-service/app/services/graph_provider.py").read_text()
hybrid = (R / "ai-service/app/services/retrieval/hybrid.py").read_text()
front = (R / "frontend/src/components/DataCenterPanel.vue").read_text()
for table in ["t_source", "t_knowledge_card", "t_claim"]:
    assert table in v10
assert "COALESCE(CAST(s.permission_tags AS CHAR),'[]') permissionTags" in mapper
assert "JSON_CONTAINS(s.permission_tags" in mapper and "JSON_CONTAINS(k.permission_tags" in mapper
assert "trusted-header-enabled:false" in resolver
assert "X-Authenticated-Permission-Tags" in controller
assert "withPermissionTags" in extract and "card.setPermissionTags" in extract and "claim.setPermissionTags" in extract
assert "self._permission_tags(card)" in indexer and "self._permission_tags(claim)" in indexer
assert "n.permissionTags=$permissionTags" in graph
assert "relationships(p)" in graph and "permissionTags" in graph
assert "any(tag IN coalesce(n.permissionTags, []) WHERE tag IN $permissionTags)" in graph
assert "PERMISSION_TAGS_PRE_TOPK_ALL_CHANNELS" in hybrid
assert "来源 ACL 标签" in front
print("authoritative ACL persistence + retrieval contract passed")
