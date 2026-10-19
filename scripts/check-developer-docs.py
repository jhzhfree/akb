#!/usr/bin/env python3
"""Ensure the developer documentation stays present and connected to the codebase."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {
    "docs/development/DEVELOPER-GUIDE.md": [
        "Transactional Outbox",
        "蓝绿",
        "Query",
        "Elasticsearch",
        "Milvus",
        "测试",
    ],
    "docs/development/ARCHITECTURE-CODE-WALKTHROUGH.md": [
        "DocumentIngestionService",
        "KnowledgeExtractionService",
        "IndexOutboxWorker",
        "IndexSchemaService",
        "HybridRetriever",
    ],
    "docs/development/CODE-STANDARDS.md": [
        "Java",
        "Python",
        "Vue",
        "事务",
        "ACL",
    ],
    "docs/development/DEBUGGING-TESTING-GUIDE.md": [
        "Outbox",
        "SOURCE_CHANGED",
        "QueryTrace",
    ],
    "docs/development/CHANGE-PLAYBOOK.md": [
        "Backend API",
        "Outbox",
        "Retrieval",
    ],
}

for relative, markers in REQUIRED.items():
    path = ROOT / relative
    assert path.exists(), "missing developer document: %s" % relative
    text = path.read_text(encoding="utf-8")
    assert len(text) >= 500, "developer document unexpectedly small: %s" % relative
    missing = [marker for marker in markers if marker not in text]
    assert not missing, "%s missing markers: %s" % (relative, missing)

readme = (ROOT / "README.md").read_text(encoding="utf-8")
assert "docs/development/DEVELOPER-GUIDE.md" in readme, "README must link the developer guide"
assert "docs/development/CODE-STANDARDS.md" in readme, "README must link code standards"

print("developer documentation contract passed: %d documents" % len(REQUIRED))
