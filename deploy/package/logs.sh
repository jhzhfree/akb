#!/usr/bin/env bash
# 查看日志：./logs.sh [服务名]，不带参数则跟随全部
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

if [ "$#" -gt 0 ]; then
  docker compose logs -f --tail=200 "$@"
else
  docker compose logs -f --tail=200
fi
