#!/usr/bin/env bash
# 状态与健康检查：容器 -> 健康端点 -> 数据卷 -> 磁盘占用
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

[ -f .env ] || { echo "未找到 .env，请先执行 ./install.sh"; exit 1; }

if [ -f release.env ]; then
  set -a; . ./release.env; set +a
fi
VERSION="${VERSION:-$(cat VERSION 2>/dev/null || echo 未知)}"

echo "=== 版本 ==="
echo "  AI Knowledge OS ${VERSION}"
if [ -f .env.example ] && ! diff -q .env .env.example >/dev/null 2>&1; then
  echo "  .env 已修改（与 .env.example 不同）"
fi

echo
echo "=== 容器 ==="
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

# 未就绪的容器单独点名：compose ps 里它们混在表格里，容易被忽略
starting="$(docker compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null \
  | grep -Ev 'healthy|^$' | grep -E 'starting|unhealthy' || true)"
if [ -n "$starting" ]; then
  echo
  echo "=== 尚未就绪 ==="
  printf '  %s\n' "$starting"
  echo "  首次启动 ES / Milvus / Neo4j 需要数分钟，属正常；持续 unhealthy 请看 ./logs.sh <服务>"
fi

echo
echo "=== 数据卷 ==="
# 卷名是 <project>_<volume>，project 默认取目录名并做规范化。
project="$(basename "$PWD" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9_-]+/-/g')"
vols="$(docker volume ls --format '{{.Name}}' 2>/dev/null | grep "^${project}_" || true)"
if [ -n "$vols" ]; then
  printf '  %s\n' "$vols"
  echo "  数据卷独立于容器：./stop.sh 不会删除，./uninstall.sh 才会。"
else
  echo "  (未找到 ${project}_* 数据卷；未启动过或 project 名不同)"
fi

echo
echo "=== 磁盘占用 ==="
docker system df | sed 's/^/  /'
