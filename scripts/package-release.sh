#!/usr/bin/env bash
# 构建发布镜像并打包为离线安装产品包
#
# 用法：
#   ./scripts/package-release.sh [VERSION]
#   VERSION=1.1.0 IMAGE_PREFIX=registry.example.com/knowledge-os ./scripts/package-release.sh
#   INCLUDE_MIDDLEWARE=0 ./scripts/package-release.sh   # 只打应用镜像，中间件在线拉取
#
# 产物：dist/ai-knowledge-<VERSION>.tar.gz
set -euo pipefail

ROOT="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-${VERSION:-1.1.0}}"
IMAGE_PREFIX="${IMAGE_PREFIX:-knowledge-os}"
INCLUDE_MIDDLEWARE="${INCLUDE_MIDDLEWARE:-1}"
OUT="${OUT:-$ROOT/dist}"
PKG_NAME="ai-knowledge-${VERSION}"
PKG="$OUT/$PKG_NAME"

export VERSION IMAGE_PREFIX

log() { printf '[package] %s\n' "$*"; }
die() { printf '[package][ERROR] %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "未检测到 docker"
docker compose version >/dev/null 2>&1 || die "未检测到 docker compose 插件"

# ---------------------------------------------------------------- 1. 构建应用镜像
log "构建应用镜像 ${IMAGE_PREFIX}/*:${VERSION}"
docker compose -f docker-compose.yml build ai-service backend frontend

APP_IMAGES=(
  "${IMAGE_PREFIX}/ai-service:${VERSION}"
  "${IMAGE_PREFIX}/backend:${VERSION}"
  "${IMAGE_PREFIX}/frontend:${VERSION}"
)

MIDDLEWARE_IMAGES=(
  mysql:8.0.36
  redis:7-alpine
  neo4j:5.26
  docker.elastic.co/elasticsearch/elasticsearch:9.5.3
  milvusdb/milvus:v3.0.1
  quay.io/coreos/etcd:v3.5.18
  minio/minio:RELEASE.2025-07-23T15-54-02Z
  prom/prometheus:v3.5.0
  grafana/grafana:12.1.1
)

IMAGES=("${APP_IMAGES[@]}")
if [ "$INCLUDE_MIDDLEWARE" = "1" ]; then
  IMAGES+=("${MIDDLEWARE_IMAGES[@]}")
fi

# ---------------------------------------------------------------- 2. 组装目录
log "组装产品目录 $PKG"
rm -rf "$PKG"
mkdir -p "$PKG/images" "$PKG/monitoring"

cp deploy/docker-compose.release.yml "$PKG/docker-compose.yml"
cp .env.production.example          "$PKG/.env.example"
cp -r monitoring/.                  "$PKG/monitoring/"
cp deploy/package/README.md         "$PKG/README.md"

for f in deploy/package/*.sh; do
  cp "$f" "$PKG/$(basename "$f")"
  chmod +x "$PKG/$(basename "$f")"
done

printf 'VERSION=%s\nIMAGE_PREFIX=%s\n' "$VERSION" "$IMAGE_PREFIX" > "$PKG/release.env"

# ---------------------------------------------------------------- 3. 导出离线镜像
save_group() {
  local archive="$1"; shift
  local existing=()
  for img in "$@"; do
    if docker image inspect "$img" >/dev/null 2>&1; then
      existing+=("$img")
    else
      printf '[package][WARN] 本地缺少镜像 %s，已跳过（目标机需能在线拉取）\n' "$img"
    fi
  done
  [ "${#existing[@]}" -eq 0 ] && return 0
  log "导出 $(basename "$archive")（${#existing[@]} 个镜像）"
  docker save "${existing[@]}" | gzip -c > "$archive"
}

save_group "$PKG/images/app-images.tar.gz" "${APP_IMAGES[@]}"
if [ "$INCLUDE_MIDDLEWARE" = "1" ]; then
  save_group "$PKG/images/middleware-images.tar.gz" "${MIDDLEWARE_IMAGES[@]}"
fi

# ---------------------------------------------------------------- 4. 校验与归档
if [ -d "$PKG/images" ] && [ -n "$(ls -A "$PKG/images" 2>/dev/null)" ]; then
  ( cd "$PKG" && sha256sum images/*.tar.gz > SHA256SUMS )
fi

mkdir -p "$OUT"
TARBALL="$OUT/${PKG_NAME}.tar.gz"
rm -f "$TARBALL"
log "归档 $TARBALL"
tar -czf "$TARBALL" -C "$OUT" "$PKG_NAME"

log "完成：$(du -h "$TARBALL" | cut -f1)  $TARBALL"
log "目标机执行：tar -xzf ${PKG_NAME}.tar.gz && cd ${PKG_NAME} && ./install.sh && ./start.sh"
