#!/usr/bin/env bash
set -euo pipefail
BACKEND="${BACKEND_URL:-http://127.0.0.1:8080}"
AI="${AI_URL:-http://127.0.0.1:12000}"
ELASTICSEARCH="${ELASTICSEARCH_DIAG_URL:-http://127.0.0.1:9200}"
MILVUS="${MILVUS_DIAG_URL:-http://127.0.0.1:6333}"
probe(){ printf '%-28s ' "$1"; curl -fsS --max-time 5 "$2" >/dev/null && echo OK || { echo FAILED; return 1; }; }
rc=0
probe 'Elasticsearch cluster' "$ELASTICSEARCH/_cluster/health" || rc=1
probe 'Milvus collections' "$MILVUS/collections" || rc=1
probe 'AI /health' "$AI/health" || rc=1
probe 'AI retrieval capabilities' "$AI/ai/retrieval/capabilities" || rc=1
probe 'Backend health' "$BACKEND/actuator/health" || rc=1
probe 'OpenAPI' "$BACKEND/v3/api-docs" || rc=1
[ "$rc" -eq 0 ] || { echo '诊断失败：检查 MySQL、Elasticsearch、Milvus、Neo4j、AI Service、索引认证/TLS 与容器日志。'; exit 1; }
echo 'Dual-retrieval startup diagnostics passed.'

printf '\n== Prometheus metrics ==\n'
curl -fsS "${BACKEND_URL:-http://127.0.0.1:8080}/actuator/prometheus" 2>/dev/null \
  | grep -E 'llmkb_index_outbox_(pending|retry|dead|oldest_lag|delivery)' | head -30 || true
