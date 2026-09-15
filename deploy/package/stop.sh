#!/usr/bin/env bash
# 停止产品（保留数据卷，可再次 start.sh 启动）
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

echo "[stop] 停止服务（保留数据卷）"
docker compose down "$@"
echo "[stop] 已停止。数据卷仍保留，执行 ./start.sh 可恢复"
