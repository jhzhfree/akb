#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
KB="${KB:-1}"; TYPE="${TYPE:-CARD}"; CODE="${CODE:-}"; STATUS="${STATUS:-APPROVED}"
if [[ -z "$CODE" ]]; then echo "Set CODE to an existing CARD/CLAIM object code" >&2; exit 2; fi
json(){ python -m json.tool; }
echo '[1] stop Milvus'; docker compose stop milvus
echo '[2] trigger status event while Milvus is down'
curl -fsS -H 'Content-Type: application/json' -H "X-Trace-Id: chaos-$(date +%s)" \
  -d "{\"objectType\":\"$TYPE\",\"objectCode\":\"$CODE\",\"decision\":\"$STATUS\",\"reviewer\":\"chaos-test\"}" \
  "$BASE_URL/api/knowledge/adjudicate" | json
echo '[3] wait for RETRY'; sleep "${WAIT_SECONDS:-5}"
curl -fsS "$BASE_URL/api/knowledge/index/outbox/$KB/stats" | json
echo '[4] restore Milvus'; docker compose start milvus
sleep "${RECOVERY_SECONDS:-15}"
echo '[5] verify backlog recovers'; curl -fsS "$BASE_URL/api/knowledge/index/outbox/$KB/stats" | json
echo '[6] inspect Prometheus metrics'; curl -fsS "$BASE_URL/actuator/prometheus" | grep -E 'llmkb_index_outbox_(delivery|pending|retry|dead|oldest_lag)' || true
