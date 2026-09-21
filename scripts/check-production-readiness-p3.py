#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
required = [
    "scripts/release-acl-live.py",
    "scripts/release-consistency-live.py",
    "scripts/release-build-qualification.sh",
    "scripts/release-go-no-go.py",
    "scripts/generate-rq-dataset.py",
    "PRODUCTION-READINESS-P3.md",
    "PRODUCTION-CAPACITY-BASELINE.md",
    "BACKUP-RECOVERY-DRILL.md",
    "GO-NO-GO-CHECKLIST.md",
]
for x in required:
    assert (ROOT / x).exists(), "missing " + x
milvus = (ROOT / "ai-service/app/services/retrieval/milvus_provider.py").read_text()
es = (ROOT / "ai-service/app/services/retrieval/elasticsearch_provider.py").read_text()
assert "json_contains_any(permission_tags" in milvus and "__public__" in milvus
assert 'body.pop("permission_tags", None)' in es
G = (ROOT / "scripts/release-go-no-go.py").read_text()
for token in [
    "acl-qualification.json",
    "consistency-qualification.json",
    "blue-green-qualification.json",
    "fault-injection-qualification.json",
    "build-qualification.json",
]:
    assert token in G, "missing mandatory evidence " + token
print("Production Readiness P3 contract passed")
