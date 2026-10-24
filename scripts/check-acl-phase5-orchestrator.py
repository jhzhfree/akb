from pathlib import Path

root = Path(__file__).resolve().parents[1]
checks = {
    "migration": "t_acl_rebuild_job"
    in (root / "backend/src/main/resources/db/migration/V14__acl_rebuild_orchestration.sql").read_text(),
    "state machine": all(
        x in (root / "backend/src/main/java/com/jh/knowledgeos/service/AclRebuildOrchestrator.java").read_text()
        for x in ["REBUILDING", "VALIDATING", "READY", "ACTIVATING", "COMPLETED"]
    ),
    "activation barrier": "activation barrier not satisfied"
    in (root / "backend/src/main/java/com/jh/knowledgeos/service/AclRebuildOrchestrator.java").read_text(),
    "qualification": "qualificationState"
    in (root / "backend/src/main/java/com/jh/knowledgeos/service/AclRebuildOrchestrator.java").read_text(),
    "change closure": "updateAclChangePropagation"
    in (root / "backend/src/main/java/com/jh/knowledgeos/service/AclRebuildOrchestrator.java").read_text(),
    "controller": "/acl/rebuild/{jobCode}/activate"
    in (root / "backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeIndexOperationsController.java").read_text(),
}
failed = [k for k, v in checks.items() if not v]
if failed:
    raise SystemExit("FAIL: " + ", ".join(failed))
print("PASS ACL Phase 5 Rebuild Orchestrator Contract")
