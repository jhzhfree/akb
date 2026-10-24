from pathlib import Path

R = Path(__file__).resolve().parents[1]
checks = {
    "v12": R / "backend/src/main/resources/db/migration/V12__acl_change_propagation.sql",
    "admin": R / "backend/src/main/java/com/jh/knowledgeos/service/AclAdministrationService.java",
    "audit": R / "backend/src/main/java/com/jh/knowledgeos/service/AclAuditService.java",
    "controller": R / "backend/src/main/java/com/jh/knowledgeos/controller/AclAdministrationController.java",
    "panel": R / "frontend/src/components/AclAdministrationPanel.vue",
}
for name, p in checks.items():
    assert p.exists(), (name, p)
assert "ACL_CHANGED" in checks["admin"].read_text()
assert "t_acl_access_audit" in (R / "backend/src/main/resources/mapper/AclPersistenceMapper.xml").read_text()
assert "/ai/index/object/acl" in (R / "ai-service/app/routes.py").read_text()
assert "requiresAuthoritativeRebuild" in (R / "ai-service/app/services/indexer.py").read_text()
assert "AclAdministrationPanel" in (R / "frontend/src/components/SystemPage.vue").read_text()
print("acl phase3 governance + propagation contract passed")
