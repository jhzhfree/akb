#!/usr/bin/env bash
# 启动产品（保持数据卷）
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

[ -f .env ] || { echo "未找到 .env，请先执行 ./install.sh"; exit 1; }

if [ -f release.env ]; then
  set -a; . ./release.env; set +a
fi
export VERSION="${VERSION:-1.1.0}"
export IMAGE_PREFIX="${IMAGE_PREFIX:-knowledge-os}"

echo "[start] 启动 AI Knowledge OS ${VERSION}"
docker compose up -d "$@"

echo
docker compose ps

echo
echo "[start] 访问地址："
echo "  前端        http://<HOST>:${PUBLIC_HTTP_PORT:-8088}"
echo "  后端 API    http://<HOST>:${BACKEND_HTTP_PORT:-8080}/swagger-ui.html"
echo "  Grafana     http://<HOST>:${GRAFANA_HTTP_PORT:-3000}"
echo "[start] 实时日志：./logs.sh [service]；状态检查：./status.sh"
