#!/usr/bin/env bash
# 状态与健康检查
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

docker compose ps

echo
echo "=== 健康端点 ==="
check() {
  local name="$1" url="$2"
  if curl -fsS -m 5 "$url" >/dev/null 2>&1; then
    printf '  %-12s OK    %s\n' "$name" "$url"
  else
    printf '  %-12s FAIL  %s\n' "$name" "$url"
  fi
}
check "frontend" "http://127.0.0.1:${PUBLIC_HTTP_PORT:-8088}/"
check "backend" "http://127.0.0.1:${BACKEND_HTTP_PORT:-8080}/actuator/health"
check "ai-service" "http://127.0.0.1:12000/health"
check "prometheus" "http://127.0.0.1:${PROMETHEUS_HTTP_PORT:-9090}/-/healthy"
check "grafana" "http://127.0.0.1:${GRAFANA_HTTP_PORT:-3000}/api/health"
