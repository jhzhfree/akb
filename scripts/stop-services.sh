#!/usr/bin/env bash
#
# 停止服务：本地裸跑进程（backend / frontend / ai-service）与 Docker 容器栈。
#
# 设计上刻意区分两类"停止"，因为它们的代价不同：
#   * 本地裸跑进程 —— 只能杀进程，端口随即释放；下次用 make dev-* 重新拉起。
#   * Docker 容器   —— 一律用 `docker compose stop` 而不是 `down`：容器与数据卷都还在，
#                      `docker compose start` 就能原样回来。只有明确要连容器一起销毁时才用
#                      `make down` / `make clean`（那两个是 down，会删容器、clean 还会删卷）。
#
# 停进程一律先 TERM，最多等 10 秒，仍未退出才 KILL——直接 -9 会让 backend 来不及
# 走完优雅关闭（连接池、Flyway/任务线程），下次启动更容易留下半截状态。
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ---------- 约定：端口与服务名 ----------
# 端口取自仓库实际配置：backend 8080（application.yml）、frontend 5173（vite.config.ts）、
# ai-service 12000（Makefile dev-ai）。改了配置请同步这里。
BACKEND_PORT=8080
FRONTEND_PORT=5173
AI_PORT=12000

COMPOSE_MAIN="docker-compose.yml"
COMPOSE_DEV="docker-compose.dev.yml"

# 应用服务（三个 compose 文件都有的那几个；index-worker 只在 release 编排里，不在此列）
APP_SERVICES="backend frontend ai-service"
# 中间件：停掉它们应用会连不上库，但数据卷保留
MIDDLEWARE_SERVICES="mysql redis elasticsearch neo4j milvus milvus-etcd milvus-minio"
# 监控
MONITOR_SERVICES="prometheus grafana"

ASSUME_YES=0

# ---------- 输出 ----------
info() { printf '\033[36m[info]\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m[ ok ]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[warn]\033[0m %s\n' "$*"; }
err()  { printf '\033[31m[fail]\033[0m %s\n' "$*" >&2; }

usage() {
  cat <<'EOF'
用法：./scripts/stop-services.sh <目标> [-y]

本地裸跑的进程（按端口 + 进程特征定位，先 TERM 后 KILL）：
  backend        停止本地 backend        （8080  / mvn spring-boot:run）
  frontend       停止本地前端 dev         （5173  / vite）
  ai             停止本地 ai-service      （12000 / uvicorn）
  local          以上三个一起停

Docker 容器（一律 stop，保留容器与数据卷，可 start 回来）：
  docker         停止应用服务   backend / frontend / ai-service   （docker-compose.yml）
  docker-dev     同上，但用 docker-compose.dev.yml
  middleware     停止中间件     mysql / redis / es / milvus / neo4j
  monitor        停止监控       prometheus / grafana
  containers     停止所有容器（含中间件与监控）
  all            本地进程 + 所有容器一起停

查看状态：
  status         列出端口占用与容器状态（不做任何停止动作）

选项：
  -y, --yes      跳过交互确认（containers / all 默认会先问一次）

常见组合：
  ./scripts/stop-services.sh status         # 先看谁在跑
  ./scripts/stop-services.sh backend        # 只停后端，改完代码再 make dev-backend
  ./scripts/stop-services.sh local          # 停掉本机裸跑的三个服务
  ./scripts/stop-services.sh middleware     # 只停中间件，应用容器留着
EOF
}

# ---------- 进程停止 ----------
# 取监听某端口的 PID（ss -tlnp 输出形如 users:(("java",pid=508446,fd=113))）
port_pids() {
  local port="$1"
  ss -tlnp 2>/dev/null | grep -F ":${port} " | grep -oP 'pid=\K[0-9]+' | sort -u
}

# 按命令行特征取 PID
pattern_pids() {
  local pattern="$1"
  pgrep -f "$pattern" 2>/dev/null | sort -u
}

# 优雅停止：TERM → 最多等 10s → KILL
terminate() {
  local pids="$1"
  [ -z "$pids" ] && return 0
  kill -TERM $pids 2>/dev/null
  for _ in $(seq 1 10); do
    sleep 1
    local alive=""
    for pid in $pids; do
      kill -0 "$pid" 2>/dev/null && alive="$alive $pid"
    done
    [ -z "$alive" ] && return 0
    pids="$alive"
  done
  warn "以下进程 10 秒内未退出，改用 KILL：$pids"
  kill -KILL $pids 2>/dev/null
}

# 停止一类本地服务：$1=名称 $2=端口 $3=进程特征
stop_local() {
  local name="$1" port="$2" pattern="$3"
  local pids
  pids="$(printf '%s\n%s\n' "$(port_pids "$port")" "$(pattern_pids "$pattern")" | grep -E '^[0-9]+$' | sort -u | tr '\n' ' ')"
  if [ -z "${pids// /}" ]; then
    ok "$name：未在端口 $port 上监听，也没有匹配「$pattern」的进程，无需处理"
    return 0
  fi
  info "停止 $name（端口 $port），PID：$pids"
  terminate "$pids"
  ok "$name 已停止"
}

# ---------- 容器停止 ----------
have_docker() {
  command -v docker >/dev/null 2>&1 || { err "未检测到 docker 命令，无法操作容器"; return 1; }
  return 0
}

# $1=compose 文件，其余为服务名
compose_stop() {
  local file="$1"; shift
  [ -f "$file" ] || { err "编排文件不存在：$file"; return 1; }
  info "停止（$file）：$*"
  docker compose -f "$file" stop "$@"
  ok "已停止（$file）：$*"
}

confirm() {
  [ "$ASSUME_YES" -eq 1 ] && return 0
  printf '\033[33m%s\033[0m [y/N] ' "$1"
  read -r answer
  case "$answer" in
    y|Y|yes|YES) return 0 ;;
    *) warn "已取消"; return 1 ;;
  esac
}

# ---------- 状态 ----------
show_status() {
  echo "── 本地端口 ──"
  for entry in "backend:$BACKEND_PORT" "frontend:$FRONTEND_PORT" "ai-service:$AI_PORT"; do
    local name="${entry%%:*}" port="${entry##*:}"
    local pids
    pids="$(port_pids "$port" | tr '\n' ' ')"
    if [ -n "${pids// /}" ]; then
      printf '  %-12s :%-6s 在跑  PID=%s\n' "$name" "$port" "$pids"
    else
      printf '  %-12s :%-6s 未运行\n' "$name" "$port"
    fi
  done
  echo "── 容器 ──"
  if have_docker; then
    if [ -f "$COMPOSE_MAIN" ]; then
      docker compose -f "$COMPOSE_MAIN" ps --format 'table {{.Name}}\t{{.Service}}\t{{.Status}}' 2>/dev/null || \
        docker compose -f "$COMPOSE_MAIN" ps
    else
      warn "未找到 $COMPOSE_MAIN"
    fi
  fi
}

# ---------- 主流程 ----------
target="${1:-}"
if [ "$target" = "-y" ] || [ "$target" = "--yes" ]; then
  ASSUME_YES=1
  target="${2:-}"
fi
for arg in "$@"; do
  case "$arg" in
    -y|--yes) ASSUME_YES=1 ;;
  esac
done

case "$target" in
  backend)
    stop_local "backend" "$BACKEND_PORT" "spring-boot:run|KnowledgeOsApplication" ;;
  frontend)
    stop_local "frontend" "$FRONTEND_PORT" "vite" ;;
  ai)
    stop_local "ai-service" "$AI_PORT" "uvicorn app.main:app" ;;
  local)
    stop_local "ai-service" "$AI_PORT" "uvicorn app.main:app"
    stop_local "frontend"  "$FRONTEND_PORT" "vite"
    stop_local "backend"   "$BACKEND_PORT" "spring-boot:run|KnowledgeOsApplication"
    ok "本地服务已全部停止" ;;
  docker)
    have_docker && compose_stop "$COMPOSE_MAIN" $APP_SERVICES ;;
  docker-dev)
    have_docker && compose_stop "$COMPOSE_DEV" $APP_SERVICES ;;
  middleware)
    have_docker || exit 1
    confirm "停止中间件（$MIDDLEWARE_SERVICES）？应用将连不上库。" && \
      compose_stop "$COMPOSE_MAIN" $MIDDLEWARE_SERVICES ;;
  monitor)
    have_docker && compose_stop "$COMPOSE_MAIN" $MONITOR_SERVICES ;;
  containers)
    have_docker || exit 1
    confirm "停止所有容器（应用 + 中间件 + 监控）？" && {
      info "停止所有容器（$COMPOSE_MAIN）"
      docker compose -f "$COMPOSE_MAIN" stop
      ok "所有容器已停止"
    } ;;
  all)
    confirm "停止本地进程 + 所有容器？" && {
      stop_local "ai-service" "$AI_PORT" "uvicorn app.main:app"
      stop_local "frontend"  "$FRONTEND_PORT" "vite"
      stop_local "backend"   "$BACKEND_PORT" "spring-boot:run|KnowledgeOsApplication"
      have_docker && docker compose -f "$COMPOSE_MAIN" stop
      ok "已全部停止"
    } ;;
  status)
    show_status ;;
  ""|-h|--help|help)
    usage ;;
  *)
    err "未知目标：$target"
    echo
    usage
    exit 1 ;;
esac
