#!/usr/bin/env bash
# =============================================================================
# 制作 AI Knowledge OS 产品安装包（docker compose 版）
#
# 产出一个 tar.gz，里面是完整的可交付单元：编排文件、管理脚本、配置模板、
# （可选）全部镜像。目标机上只要装了 docker 与 docker compose 插件，解开即可安装。
#
# 两种形态：
#   在线包  默认。包里不含镜像，安装时从镜像仓库拉取。包只有几百 KB。
#   离线包  --offline。把 compose 里出现的镜像全部 docker save 进去，
#           目标机无需外网。代价是包很大（十余 GB），且必须与版本严格对应。
#
# 用法：
#   ./pack.sh                          # 在线包
#   ./pack.sh --offline --build        # 离线包：先构建业务镜像，再导出全部镜像
#   ./pack.sh --version 1.2.0 --prefix registry.example.com/knowledge-os
#   ./pack.sh --out /tmp               # 指定产物目录
#
# 产物：
#   deploy/dist/ai-knowledge-os-<VERSION>[-offline]-<日期>.tar.gz
#   同名的 .sha256 校验文件
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VERSION=""
PREFIX="knowledge-os"
OFFLINE=0
DO_BUILD=0
DO_PULL=0
OUT_DIR="$SCRIPT_DIR/dist"
COMPOSE_SRC="$SCRIPT_DIR/docker-compose.release.yml"
PROD_COMPOSE="$SCRIPT_DIR/docker-compose.prod.yml"

log()  { printf '[pack] %s\n' "$*"; }
warn() { printf '[pack][WARN] %s\n' "$*" >&2; }
die()  { printf '[pack][ERROR] %s\n' "$*" >&2; exit 1; }

usage() {
  sed -n '3,26p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --version)   VERSION="${2:?--version 需要参数}"; shift 2 ;;
    --prefix)    PREFIX="${2:?--prefix 需要参数}";  shift 2 ;;
    --offline)   OFFLINE=1; shift ;;
    --build)     DO_BUILD=1; shift ;;
    --pull)      DO_PULL=1; shift ;;
    --out)       OUT_DIR="${2:?--out 需要参数}"; shift 2 ;;
    -h|--help)   usage ;;
    *)           die "未知参数: $1（用 --help 查看用法）" ;;
  esac
done

# ---------------------------------------------------------------------------
# 1. 环境检查
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "未检测到 docker"
docker compose version >/dev/null 2>&1 || die "未检测到 docker compose 插件（v2）"
[ -f "$COMPOSE_SRC" ]     || die "缺少编排文件: $COMPOSE_SRC"
[ -f "$PROD_COMPOSE" ]    || die "缺少构建用编排文件: $PROD_COMPOSE"
[ -d "$SCRIPT_DIR/package" ] || die "缺少脚本目录: $SCRIPT_DIR/package"
[ -f "$SCRIPT_DIR/package/.env.example" ] || die "缺少配置模板: package/.env.example"

# 版本未指定时取前端 package.json：三个业务镜像共用这一个版本号。
if [ -z "$VERSION" ]; then
  VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$REPO_ROOT/frontend/package.json" | head -1)"
  [ -n "$VERSION" ] || die "无法从 frontend/package.json 读取版本，请用 --version 指定"
fi

export VERSION IMAGE_PREFIX="$PREFIX"
STAMP="$(date +%Y%m%d)"
SUFFIX="$VERSION"
[ "$OFFLINE" -eq 1 ] && SUFFIX="$SUFFIX-offline"
PKG_NAME="ai-knowledge-os-$SUFFIX-$STAMP"
STAGE="$OUT_DIR/.build/$PKG_NAME"

log "AI Knowledge OS $VERSION  镜像前缀=${PREFIX}  形态=$([ "$OFFLINE" -eq 1 ] && echo 离线 || echo 在线)"

rm -rf "$STAGE"
mkdir -p "$STAGE/images"

# ---------------------------------------------------------------------------
# 2. 组装包内容
# ---------------------------------------------------------------------------
log "组装 $PKG_NAME"

cp "$COMPOSE_SRC" "$STAGE/docker-compose.yml"
cp "$SCRIPT_DIR/package/.env.example" "$STAGE/.env.example"
for f in "$SCRIPT_DIR"/package/*.sh; do
  [ -e "$f" ] || continue
  cp "$f" "$STAGE/"
  chmod +x "$STAGE/$(basename "$f")"
done
[ -f "$SCRIPT_DIR/package/README.md" ] && cp "$SCRIPT_DIR/package/README.md" "$STAGE/README.md"

# 版本号与镜像前缀写进 release.env：install/start 靠它定位镜像，避免手写错版本。
cat > "$STAGE/release.env" <<EOF
# 由 pack.sh 生成，勿手工编辑：改版本请重新打包。
VERSION=$VERSION
IMAGE_PREFIX=$PREFIX
EOF
printf '%s\n' "$VERSION" > "$STAGE/VERSION"

# 出厂默认配置：场景模板与衍生规则。放进包里是给运维一个可对照、可直接放回
# 配置目录的副本，容器内的 /app/config 由 backend 首次启动时播种。
if [ -d "$REPO_ROOT/backend/src/main/resources/defaults" ]; then
  mkdir -p "$STAGE/config"
  cp "$REPO_ROOT"/backend/src/main/resources/defaults/*.json "$STAGE/config/" 2>/dev/null || true
fi

# Prometheus / Grafana 的配置是 bind 挂载（./monitoring/...），必须随包一起走。
# 少了它容器照样起，只是没有抓取规则和面板，问题要到排查时才被发现。
[ -d "$REPO_ROOT/monitoring" ] || die "缺少监控配置目录: $REPO_ROOT/monitoring"
cp -r "$REPO_ROOT/monitoring" "$STAGE/monitoring"

# ---------------------------------------------------------------------------
# 3. 校验编排文件（顺带拿到镜像清单）
# ---------------------------------------------------------------------------
# compose 的变量替换要读 .env，这里临时造一份再删掉；包里只保留 .env.example。
IMAGE_LIST="$STAGE/.images"
list_images() {
  ( cd "$STAGE"
    cp .env.example .env
    docker compose -f docker-compose.yml config --images 2>/dev/null || true
    rm -f .env
  ) | sed 's/[[:space:]]*$//' | grep -v '^$' | sort -u
}

list_images > "$IMAGE_LIST"
if [ ! -s "$IMAGE_LIST" ]; then
  # 老版本 compose 没有 --images，退回到解析 image: 行。
  warn "docker compose config --images 无输出，改用解析 image: 行"
  grep -E '^[[:space:]]+image:' "$STAGE/docker-compose.yml" \
    | sed -E 's/.*image:[[:space:]]*//' | tr -d '"'"'" \
    | sed -e "s#\\\${IMAGE_PREFIX[^}]*}#$PREFIX#g" -e "s#\\\${VERSION[^}]*}#$VERSION#g" \
    | sort -u > "$IMAGE_LIST"
fi
[ -s "$IMAGE_LIST" ] || die "无法解析镜像清单，请检查 docker-compose.release.yml"

IMG_COUNT="$(wc -l < "$IMAGE_LIST" | tr -d ' ')"
log "编排文件校验通过：$IMG_COUNT 个镜像"

# ---------------------------------------------------------------------------
# 4. （离线）准备并导出镜像
# ---------------------------------------------------------------------------
if [ "$OFFLINE" -eq 1 ]; then
  [ "$DO_BUILD" -eq 1 ] && {
    log "构建业务镜像（ai-service / backend / frontend）"
    docker compose -f "$PROD_COMPOSE" build ai-service backend index-worker frontend
  }
  [ "$DO_PULL" -eq 1 ] && {
    log "拉取依赖镜像"
    ( cd "$STAGE" && cp .env.example .env && docker compose pull; rm -f .env ) \
      || warn "部分镜像拉取失败，后续会列出缺失项"
  }

  missing=()
  while read -r img; do
    [ -n "$img" ] || continue
    if ! docker image inspect "$img" >/dev/null 2>&1; then
      missing+=("$img")
      continue
    fi
    safe="$(printf '%s' "$img" | tr '/:' '--')"
    log "导出 $img"
    docker save "$img" | gzip -1 > "$STAGE/images/${safe}.tar.gz"
  done < "$IMAGE_LIST"

  if [ "${#missing[@]}" -gt 0 ]; then
    printf '[pack][ERROR] 以下镜像在本机不存在，无法制作离线包：\n' >&2
    printf '           %s\n' "${missing[@]}" >&2
    printf '[pack][ERROR] 业务镜像加 --build 构建；依赖镜像加 --pull 拉取\n' >&2
    exit 1
  fi
  log "共导出 $IMG_COUNT 个镜像，$(du -sh "$STAGE/images" | cut -f1)"
else
  rmdir "$STAGE/images" 2>/dev/null || true
  log "在线包：镜像将在目标机安装时从仓库拉取"
fi

rm -f "$IMAGE_LIST"

# ---------------------------------------------------------------------------
# 5. 打包
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"
TARBALL="$OUT_DIR/$PKG_NAME.tar.gz"
log "打包 $TARBALL"
tar -czf "$TARBALL" -C "$OUT_DIR/.build" "$PKG_NAME"
( cd "$OUT_DIR" && sha256sum "$PKG_NAME.tar.gz" > "$PKG_NAME.tar.gz.sha256" )

SIZE="$(du -sh "$TARBALL" | cut -f1)"
SUM="$(cut -d' ' -f1 "$TARBALL.sha256")"

cat <<EOF

[pack] 完成
  产物   $TARBALL
  大小   $SIZE
  sha256 $SUM
  形态   $([ "$OFFLINE" -eq 1 ] && echo "离线（含 $IMG_COUNT 个镜像）" || echo "在线（安装时拉取镜像）")

  目标机：tar -xzf $(basename "$TARBALL") && cd $PKG_NAME && ./install.sh
EOF
