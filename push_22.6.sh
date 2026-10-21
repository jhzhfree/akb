#!/usr/bin/env bash
# 一键把本地代码重新打包成应用镜像，并推送到 192.168.22.6:5000 —— 版本号保持不变（覆盖式发布）。
#
# 处理三个应用镜像：ai-service / backend / frontend（中间件镜像不动）。
# 版本不变 = registry 上的同名 tag（默认 1.1.0）被新镜像覆盖，目标机不需要改 compose 文件，
# 直接 docker compose pull && docker compose up -d 即生效。
#
# 真正的构建与推送在 scripts/release-push.sh，本脚本只负责收参数、校验、推送前确认。
#
# 用法：
#   ./push_22.6.sh                    # 三个镜像全量重建 + 推送 22.6（版本不变）
#   ./push_22.6.sh frontend           # 只做前端（改动只涉及前端时用，几十秒）
#   ./push_22.6.sh backend frontend   # 指定多个
#   ./push_22.6.sh --dry-run          # 只打印将要执行的命令，不真跑
#   ./push_22.6.sh --no-push          # 只构建 + 打本地 tag，不推送（先本地验证）
#   ./push_22.6.sh --no-backup        # 不备份 registry 上的旧 tag（默认备份为 <version>-prev）
#   ./push_22.6.sh --yes              # 跳过推送前的确认（用于脚本串联）
#   VERSION=1.2.0 ./push_22.6.sh      # 只有真要换版本号时才指定
#
# 失败即停：任一服务构建失败不会继续推后面的服务。
set -euo pipefail

ROOT="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
cd "$ROOT"

VERSION="${VERSION:-1.1.0}"
REGISTRY="${REGISTRY:-192.168.22.6:5000}"
PUSH=1
BACKUP=1
DRY_RUN=0
ASSUME_YES=0
VALID_SERVICES=(ai-service backend frontend)
SERVICES=()

usage() {
  sed -n '2,20p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
  exit 0
}

die() { printf '[push_22.6][ERROR] %s\n' "$*" >&2; exit 1; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    -n|--dry-run) DRY_RUN=1; ASSUME_YES=1; shift ;;
    --no-push)    PUSH=0;    shift ;;
    --no-backup)  BACKUP=0;  shift ;;
    -y|--yes)     ASSUME_YES=1; shift ;;
    -h|--help)    usage ;;
    -*)           die "未知参数：$1（用 --help 看用法）" ;;
    *)            SERVICES+=("$1"); shift ;;
  esac
done

# 服务名校验：手滑写成 ai / back-end 会在构建阶段才炸，这里先拦掉
for s in ${SERVICES[@]+"${SERVICES[@]}"}; do
  found=0
  for v in "${VALID_SERVICES[@]}"; do [ "$s" = "$v" ] && found=1; done
  [ "$found" = "1" ] || die "未知服务：$s（可选：${VALID_SERVICES[*]}）"
done
[ "${#SERVICES[@]}" -gt 0 ] || SERVICES=("${VALID_SERVICES[@]}")

ACTION="构建 + 推送"
[ "$PUSH" = "1" ] || ACTION="只构建（不推送）"

printf '[push_22.6] registry : %s\n' "$REGISTRY"
printf '[push_22.6] version  : %s（不变，覆盖式发布）\n' "$VERSION"
printf '[push_22.6] services : %s\n' "${SERVICES[*]}"
printf '[push_22.6] 动作     : %s；旧 tag 备份：%s\n' "$ACTION" "$([ "$BACKUP" = "1" ] && echo "是（<version>-prev）" || echo "否")"

if [ "$PUSH" = "1" ] && [ "$ASSUME_YES" != "1" ]; then
  printf '[push_22.6] 这会覆盖 %s 上这些 tag，确认继续？[y/N] ' "$REGISTRY"
  read -r reply
  case "$reply" in
    y|Y|yes|YES) ;;
    *) echo '[push_22.6] 已取消'; exit 0 ;;
  esac
fi

VERSION="$VERSION" REGISTRY="$REGISTRY" PUSH="$PUSH" BACKUP="$BACKUP" DRY_RUN="$DRY_RUN" \
  ./scripts/release-push.sh "${SERVICES[@]}"

if [ "$DRY_RUN" = "1" ]; then
  exit 0
fi

cat <<TIP

[push_22.6] 完成。目标机（22.6）更新：
    docker compose pull && docker compose up -d
TIP

if [ "$BACKUP" = "1" ] && [ "$PUSH" = "1" ]; then
  cat <<TIP
回滚（把备份的旧 tag 打回来再推）：
TIP
  for s in "${SERVICES[@]}"; do
    printf '    docker tag %s/knowledge-os/%s:%s-prev %s/knowledge-os/%s:%s && docker push %s/knowledge-os/%s:%s\n' \
      "$REGISTRY" "$s" "$VERSION" "$REGISTRY" "$s" "$VERSION" "$REGISTRY" "$s" "$VERSION"
  done
fi
