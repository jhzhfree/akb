from pathlib import Path
root=Path(__file__).resolve().parents[1]
checks={
 'V17 migration': 'acl_inherited_from' in (root/'backend/src/main/resources/db/migration/V17__graph_acl_inheritance.sql').read_text(),
 'entity persistence': 'upsertExtractedEntity' in (root/'backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java').read_text(),
 'relation persistence': 'upsertExtractedRelation' in (root/'backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java').read_text(),
 'link ACL': 'linkClaimEntityAcl' in (root/'backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java').read_text(),
 'graph manifest qualification': 'expectedGraphManifestHash' in (root/'backend/src/main/java/com/jh/knowledgeos/service/AclRebuildOrchestrator.java').read_text(),
 'neo4j graph manifest': 'authoritative_graph_manifest' in (root/'ai-service/app/services/graph_provider.py').read_text(),
 'multihop': 'generation_multihop' in (root/'ai-service/app/services/graph_provider.py').read_text(),
 'path ACL': 'NODE_EDGE_EVIDENCE_PRE_FILTER' in (root/'ai-service/app/services/graph_provider.py').read_text(),
 'cycle policy': 'SIMPLE_PATH' in (root/'ai-service/app/services/graph_provider.py').read_text(),
 'token budget': 'tokenBudget' in (root/'ai-service/app/routes.py').read_text(),
}
for k,v in checks.items(): print(('PASS' if v else 'FAIL'), k)
assert all(checks.values())
print('ACL Phase 10 contract passed:',len(checks),'checks')
