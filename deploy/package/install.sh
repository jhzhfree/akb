#!/usr/bin/env bash
# 离线安装：预检环境 -> 加载镜像 -> 生成 .env
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

log() { printf '[install] %s\n' "$*"; }
die() { printf '[install][ERROR] %s\n' "$*" >&2; exit 1; }

if [ -f release.env ]; then
  set -a; . ./release.env; set +a
fi
VERSION="${VERSION:-1.1.0}"
IMAGE_PREFIX="${IMAGE_PREFIX:-knowledge-os}"

log "AI Knowledge OS ${VERSION}"

# 1. 依赖检查
command -v docker >/dev/null 2>&1 || die "未检测到 docker，请先安装 Docker 20.10+"
docker compose version >/dev/null 2>&1 || die "未检测到 docker compose 插件（v2），请参考 https://docs.docker.com/compose/install/"

# 2. 加载离线镜像
shopt -s nullglob
tarballs=(images/*.tar.gz)
if [ "${#tarballs[@]}" -gt 0 ]; then
  for f in "${tarballs[@]}"; do
    log "加载镜像包 $(basename "$f")"
    docker load -i "$f" >/dev/null
  done
  log "共加载 ${#tarballs[@]} 个镜像包"
else
  log "images/ 下无离线镜像，将尝试从镜像仓库在线拉取"
fi
shopt -u nullglob

# 3. 生成 .env
if [ -f .env ]; then
  log ".env 已存在，保留现有配置"
else
  cp .env.example .env
  log "已生成 .env（源自 .env.example）"
fi

# 4. 必填项检查
warn=0
if grep -q 'CHANGE_ME' .env 2>/dev/null; then
  printf '[install][WARN] .env 中仍存在 CHANGE_ME 占位值，上线前必须修改：\n'
  grep -n 'CHANGE_ME' .env | sed 's/^/           /'
  warn=1
fi
if ! grep -q '^LLM_BASE_URL=.\+' .env 2>/dev/null; then
  printf '[install][WARN] 未配置 LLM_BASE_URL，ai-service 将回退到 http://127.0.0.1:11434\n'
  warn=1
fi

log "安装完成"
if [ "$warn" -eq 1 ]; then
  log "请先编辑 .env，再执行 ./start.sh 启动"
else
  log "执行 ./start.sh 启动产品"
fi
