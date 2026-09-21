#!/usr/bin/env python3
"""Static contract for ACL Phase 4 propagation-state engineering closure."""

from pathlib import Path

R = Path(__file__).resolve().parents[1]
worker = (R / "backend/src/main/java/com/jh/knowledgeos/service/IndexOutboxWorker.java").read_text()
mapper = (R / "backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text()
audit = (R / "backend/src/main/java/com/jh/knowledgeos/service/AclAuditService.java").read_text()
ui = (R / "frontend/src/components/AclAdministrationPanel.vue").read_text()
migration = (R / "backend/src/main/resources/db/migration/V13__acl_propagation_state.sql").read_text()
for token in ["REBUILD_REQUIRED", "COMPLETED", "FAILED", "updateAclChangePropagation"]:
    assert token in worker
for token in ["propagation_result", "last_error", "updateAclChangePropagation"]:
    assert token in mapper
assert "log.error" in audit and "catch (Exception ignored)" not in audit
assert "REBUILD_REQUIRED" in ui
assert "propagation_result" in migration and "completed_time" not in migration
print("acl phase4 engineering closure contract passed")
