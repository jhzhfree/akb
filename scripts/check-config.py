#!/usr/bin/env python3
from pathlib import Path
import re
s=Path(".env.example").read_text();required=["DB_USERNAME","DB_PASSWORD","AI_SERVICE_BASE_URL","NEO4J_PASSWORD","LLM_PROVIDER","EMBEDDING_PROVIDER","RERANKER_ENABLED","INDEX_OUTBOX_ENABLED","INDEX_OUTBOX_BATCH_SIZE","INDEX_OUTBOX_MAX_ATTEMPTS"]
missing=[x for x in required if not re.search(r"^"+re.escape(x)+r"=",s,re.M)]
assert not missing,"missing .env.example keys: "+",".join(missing)
print("config contract passed:",len(required),"required keys")
