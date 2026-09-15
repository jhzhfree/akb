#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration_dir = ROOT / "backend/src/main/resources/db/migration"
mapper = (ROOT / "backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text(encoding="utf-8")
sql = "\n".join(p.read_text(encoding="utf-8") for p in sorted(migration_dir.glob("V*__*.sql")))

created = set(re.findall(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-zA-Z0-9_]+)", sql, flags=re.I))
referenced = set(re.findall(r"\b(?:FROM|JOIN|INTO|UPDATE)\s+(t_[a-zA-Z0-9_]+)", mapper, flags=re.I))
missing = sorted(referenced - created)
assert not missing, "Mapper references tables missing from Flyway migrations: %s" % missing

required_tables = {
    "t_source", "t_source_version", "t_document", "t_content_unit", "t_evidence",
    "t_claim", "t_knowledge_card", "t_claim_evidence", "t_knowledge_evidence",
    "t_knowledge_conflict", "t_adjudication_assessment", "t_query_trace",
}
assert required_tables <= created, "Missing required tables: %s" % sorted(required_tables - created)

required_trace_columns = {
    "trace_id", "rewritten_queries_json", "router_decision_json", "retrieval_json",
    "fusion_json", "rerank_json", "selected_context_json", "citations_json",
    "model_name", "latency_ms", "token_usage_json", "error_code",
}
trace_sql = "\n".join(line for line in sql.splitlines() if "t_query_trace" in line or "ADD COLUMN" in line)
missing_cols = [c for c in required_trace_columns if c.lower() not in sql.lower()]
assert not missing_cols, "Missing query trace columns: %s" % missing_cols
print("db contract passed: tables=%d mapper_tables=%d trace_columns=%d" % (len(created), len(referenced), len(required_trace_columns)))
