#!/usr/bin/env python3
from pathlib import Path

r = Path(__file__).resolve().parents[1]
orch = (r / "backend/src/main/java/com/jh/knowledgeos/service/AclRebuildOrchestrator.java").read_text()
ctl = (r / "backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeController.java").read_text()
idx = (r / "ai-service/app/services/indexer.py").read_text()
checks = {
    "manual passed removed": "@RequestParam boolean passed" not in ctl and "Map<String,Object> evidence)" not in ctl,
    "machine endpoint": "INDEX_SCHEMA_ACL_QUALIFY_PREFIX" in orch
    and "acl-qualify" in (r / "ai-service/app/routes.py").read_text(),
    "personas": all(x in idx for x in ["anonymous", "team-a", "team-b", "admin"]),
    "green physical target": "qualify_acl_target" in idx and "schema_version" in idx,
    "leak fails gate": "if leaked:" in idx and '"qualified": not failures' in idx,
    "activation barrier": "qualificationState" in orch and "activation barrier not satisfied" in orch,
    "no alias activation in qualifier": "activate_target"
    not in idx[idx.index("async def qualify_schema_acl") : idx.index("async def external_stats")],
}
failed = [k for k, v in checks.items() if not v]
for k, v in checks.items():
    print(("PASS" if v else "FAIL"), k)
if failed:
    raise SystemExit("failed: " + ",".join(failed))
