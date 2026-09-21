from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "V19 facet schema": "t_entity_facet"
    in (ROOT / "backend/src/main/resources/db/migration/V19__evidence_scoped_graph_facets.sql").read_text(),
    "entity facet persistence": "upsertEntityFacet"
    in (ROOT / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java").read_text(),
    "relation facet persistence": "upsertRelationFacet"
    in (ROOT / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java").read_text(),
    "authoritative facet snapshot": "ENTITY_FACET"
    in (ROOT / "backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text(),
    "facet manifest persisted": "insertGraphFacetManifest"
    in (ROOT / "backend/src/main/java/com/jh/knowledgeos/service/AuthoritativeGraphGenerationService.java").read_text(),
    "neo4j entity facet": "EntityFacetGen" in (ROOT / "ai-service/app/services/graph_provider.py").read_text(),
    "neo4j relation facet": "RelationFacetGen" in (ROOT / "ai-service/app/services/graph_provider.py").read_text(),
    "facet manifest qualification": "entityFacetCount"
    in (ROOT / "ai-service/app/services/graph_provider.py").read_text(),
    "facet multihop": "facet_multihop" in (ROOT / "ai-service/app/services/graph_provider.py").read_text(),
    "facet API": "facet-multihop" in (ROOT / "ai-service/app/routes.py").read_text(),
    "non broadening policy": "FACET_PRE_FILTER_NON_BROADENING"
    in (ROOT / "ai-service/app/services/graph_provider.py").read_text(),
}
failed = [k for k, v in checks.items() if not v]
for k, v in checks.items():
    print(("PASS" if v else "FAIL"), k)
if failed:
    raise SystemExit("failed: " + ",".join(failed))
print("ACL Phase 12 contract passed:", len(checks), "checks")
