#!/usr/bin/env bash
# 卸载：停止服务并删除数据卷（不可恢复）
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

echo "[uninstall] 该操作将删除所有容器与数据卷（MySQL/Neo4j/Milvus/ES 数据不可恢复）"
printf '请输入 yes 确认：'
read -r confirm
[ "$confirm" = "yes" ] || { echo "[uninstall] 已取消"; exit 0; }

docker compose down -v --remove-orphans
echo "[uninstall] 已完成。镜像仍保留在本机，如需删除请手动执行 docker rmi"
