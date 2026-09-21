from pathlib import Path

R = Path(__file__).resolve().parents[1]
xml = (R / "backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text()
resolver = (R / "backend/src/main/java/com/jh/knowledgeos/service/AclContextResolver.java").read_text()
controller = (R / "backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeController.java").read_text()
migration = (R / "backend/src/main/resources/db/migration/V11__acl_identity_rbac_abac.sql").read_text()
for table in [
    "t_acl_subject",
    "t_acl_role",
    "t_acl_subject_role",
    "t_acl_role_tag",
    "t_acl_subject_tag",
    "t_acl_department_tag",
    "t_acl_access_audit",
]:
    assert table in migration
assert "X-Authenticated-User-Id" in resolver
assert "resolveAclPermissionTags" in resolver
for stmt in ["listCards", "listClaims", "listSources"]:
    start = xml.index('id="' + stmt + '"')
    block = xml[start : start + 1300]
    assert "permission_tags" in block and "JSON_CONTAINS" in block
for route in ["/cards/{kb}", "/claims/{kb}", "/sources/{kb}"]:
    start = controller.index(route)
    assert "aclContextResolver.resolve" in controller[start : start + 500]
print("ACL Phase 2 identity + list leakage contract passed")
