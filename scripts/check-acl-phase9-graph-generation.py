from pathlib import Path

r = Path(__file__).resolve().parents[1]
checks = {
    "V16 migration": "t_index_graph_manifest"
    in (r / "backend/src/main/resources/db/migration/V16__authoritative_graph_generation.sql").read_text(),
    "entity ACL": "t_entity ADD COLUMN source_id"
    in (r / "backend/src/main/resources/db/migration/V16__authoritative_graph_generation.sql").read_text(),
    "relation ACL": "t_relation ADD COLUMN source_id"
    in (r / "backend/src/main/resources/db/migration/V16__authoritative_graph_generation.sql").read_text(),
    "claim entity": "t_claim_entity"
    in (r / "backend/src/main/resources/db/migration/V16__authoritative_graph_generation.sql").read_text(),
    "card entity": "t_card_entity"
    in (r / "backend/src/main/resources/db/migration/V16__authoritative_graph_generation.sql").read_text(),
    "authoritative mapper": "listAuthoritativeGraphSnapshot"
    in (r / "backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text(),
    "graph service": (
        r / "backend/src/main/java/com/jh/knowledgeos/service/AuthoritativeGraphGenerationService.java"
    ).exists(),
    "graph endpoint": "/ai/index/schema/graph-rebuild/" in (r / "ai-service/app/routes.py").read_text(),
    "generation entity": "EntityGen" in (r / "ai-service/app/services/graph_provider.py").read_text(),
    "generation relation": "RELATION_GEN" in (r / "ai-service/app/services/graph_provider.py").read_text(),
    "knowledge entity links": "ABOUT_ENTITY_GEN" in (r / "ai-service/app/services/graph_provider.py").read_text(),
    "active traversal": "generation_neighborhood" in (r / "ai-service/app/services/graph_provider.py").read_text(),
}
for k, v in checks.items():
    print(("PASS" if v else "FAIL"), k)
assert all(checks.values())
print("ACL Phase 9 contract:", len(checks), "checks passed")
