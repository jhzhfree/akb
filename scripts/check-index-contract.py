#!/usr/bin/env python3
from pathlib import Path
sql="\n".join(p.read_text().lower() for p in sorted(Path("backend/src/main/resources/db/migration").glob("V*__*.sql")))
required=["idx_claim_lookup","idx_card_updated","idx_evidence_source_status","idx_trace_trace_id","idx_trace_kb_time","idx_conflict_status"]
missing=[x for x in required if x not in sql]
assert not missing,"missing required performance indexes: "+",".join(missing)
print("index contract passed:",len(required),"required indexes")
