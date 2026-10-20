#!/usr/bin/env python3
import re
from pathlib import Path

s = Path(".env.example").read_text()
required = [
    "DB_USERNAME",
    "DB_PASSWORD",
    "AI_SERVICE_BASE_URL",
    "NEO4J_PASSWORD",
    "LLM_PROVIDER",
    "EMBEDDING_PROVIDER",
    "RERANKER_ENABLED",
    "INDEX_OUTBOX_ENABLED",
    "INDEX_OUTBOX_BATCH_SIZE",
    "INDEX_OUTBOX_MAX_ATTEMPTS",
    "RETRIEVAL_STATUS_POLICY",
    "RETRIEVAL_ALLOWED_STATUSES",
]
missing = [x for x in required if not re.search(r"^" + re.escape(x) + r"=", s, re.M)]
assert not missing, "missing .env.example keys: " + ",".join(missing)
print("config contract passed:", len(required), "required keys")
