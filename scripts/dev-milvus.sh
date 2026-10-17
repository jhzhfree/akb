#!/usr/bin/env bash
# Milvus（双路检索的向量通道）本地启停。
#
#   ./scripts/dev-milvus.sh up       预检 + 拉镜像 + 启动 etcd/minio/milvus + 等健康 + 验证
#   ./scripts/dev-milvus.sh status   容器状态 / 健康探针 / 集合列表 / ai-service 视角的能力
#   ./scripts/dev-milvus.sh enable   把 ai-service 的持久化检索设置里 milvus 打开（并关闭则 disable）
#   ./scripts/dev-milvus.sh down     停止容器，保留数据卷（下次 up 数据还在）
#   ./scripts/dev-milvus.sh reset    停止并删除数据卷：集合全部丢失，需要重建索引
#
# 为什么脚本里到处是 --noproxy：本机设了 http_proxy=127.0.0.1:7890，curl/httpx 会把
# 127.0.0.1:19530 也交给代理，代理回 502——这正是没起 Milvus 时日志里那串 502 的来源。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE="docker compose --env-file .env.dev -f docker-compose.dev.yml --profile dual-retrieval"
SERVICES="milvus-etcd milvus-minio milvus"
SETTINGS_FILE="ai-service/data/retrieval_settings.json"
NO_PROXY_ARGS=(--noproxy '*')
PORTS=(19530 9091 2379 9000 9001)

log() { printf '\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31m[fail] %s\033[0m\n' "$*" >&2; exit 1; }
ok() { printf '    %s\n' "$*"; }

probe() {
  # Milvus 3.x 的 gRPC 健康在 9091，REST 在 19530；两个都通才算能服务。
  curl -sf -m 5 "${NO_PROXY_ARGS[@]}" http://127.0.0.1:9091/healthz >/dev/null 2>&1 || return 1
  curl -sf -m 5 "${NO_PROXY_ARGS[@]}" -X POST http://127.0.0.1:19530/v2/vectordb/collections/list \
    -H 'Content-Type: application/json' -d '{}' >/dev/null 2>&1 || return 1
  return 0
}

preflight() {
  log "预检"
  command -v docker >/dev/null || die "未安装 docker"
  docker compose version >/dev/null 2>&1 || die "docker compose 插件不可用"
  test -f .env.dev || cp .env.dev.example .env.dev
  for p in "${PORTS[@]}"; do
    if ss -lntp 2>/dev/null | grep -q ":${p}\b"; then
      die "端口 ${p} 已被占用，先停掉占用进程：ss -lntp | grep ':${p}'"
    fi
  done
  local avail
  avail=$(free -g | awk '/^Mem:/{print $7}')
  if [ "${avail:-0}" -lt 4 ]; then
    die "可用内存 ${avail}G，Milvus standalone 建议 ≥4G"
  fi
  ok "端口空闲、可用内存 ${avail}G"
}

cmd_up() {
  preflight
  log "启动 ${SERVICES}（首次要拉 milvus/etcd/minio 镜像，几分钟）"
  $COMPOSE up -d $SERVICES
  log "等待健康（最多 240s）"
  local i
  for i in $(seq 1 48); do
    if probe; then
      ok "第 $((i*5))s 起可用"
      status_detail
      return 0
    fi
    sleep 5
  done
  die "240s 内未就绪，看日志：make dev-logs 或 docker logs kos-dev-milvus"
}

cmd_status() {
  log "容器"
  $COMPOSE ps $SERVICES || true
  status_detail
}

status_detail() {
  log "健康探针"
  if probe; then ok "19530 REST + 9091 healthz 均可用"; else echo "    未就绪"; fi
  log "集合列表"
  curl -s -m 5 "${NO_PROXY_ARGS[@]}" -X POST http://127.0.0.1:19530/v2/vectordb/collections/list \
    -H 'Content-Type: application/json' -d '{}' | head -c 300 || true
  echo
  log "ai-service 视角（12000 未起则为空）"
  curl -s -m 5 "${NO_PROXY_ARGS[@]}" http://127.0.0.1:12000/ai/retrieval/capabilities | head -c 400 || true
  echo
}

toggle() {
  local want=$1
  test -f "$SETTINGS_FILE" || die "缺少 $SETTINGS_FILE"
  python3 - "$SETTINGS_FILE" "$want" <<'PY'
import json, sys, pathlib
path, want = pathlib.Path(sys.argv[1]), sys.argv[2] == 'true'
data = json.loads(path.read_text(encoding='utf-8'))
data.setdefault('milvus', {})['enabled'] = want
path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('    milvus.enabled =', want)
PY
}

cmd_down() {
  log "停止容器（保留数据卷）"
  $COMPOSE stop $SERVICES
}

cmd_reset() {
  log "停止并删除 Milvus 数据卷"
  read -r -p "    这会清空 dev_etcd_data / dev_minio_data / dev_milvus_data，确认？[y/N] " ans
  [ "$ans" = "y" ] || { echo "    已取消"; exit 0; }
  $COMPOSE down -v $SERVICES
}

case "${1:-}" in
  up) cmd_up ;;
  status) cmd_status ;;
  enable) log "打开 Milvus 通道"; toggle true; echo "    重启 ai-service 生效：make dev-ai" ;;
  disable) log "关闭 Milvus 通道"; toggle false; echo "    重启 ai-service 生效：make dev-ai" ;;
  down) cmd_down ;;
  reset) cmd_reset ;;
  *) sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 1 ;;
esac
