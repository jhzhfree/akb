from pathlib import Path

R = Path(__file__).resolve().parents[1]
checks = {
    "V18 migration": "t_entity_acl_scope"
    in (R / "backend/src/main/resources/db/migration/V18__cross_source_acl_scopes.sql").read_text(),
    "entity contribution persistence": "insertEntityAclScope"
    in (R / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java").read_text(),
    "relation contribution persistence": "insertRelationAclScope"
    in (R / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java").read_text(),
    "non broadening sentinel": "__evidence_scoped__"
    in (R / "backend/src/main/resources/mapper/GraphMapper.xml").read_text(),
    "entity merge policy": "recomputeEntityAclMergePolicy"
    in (R / "backend/src/main/resources/mapper/GraphMapper.xml").read_text(),
    "relation merge policy": "recomputeRelationAclMergePolicy"
    in (R / "backend/src/main/resources/mapper/GraphMapper.xml").read_text(),
    "stable scope hash": "aclScopeHash"
    in (R / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java").read_text(),
}
for k, v in checks.items():
    print(("PASS" if v else "FAIL"), k)
assert all(checks.values())
print("ACL Phase 11 contract:", len(checks), "checks PASS")
