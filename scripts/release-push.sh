#!/usr/bin/env bash
# 重新构建应用镜像并推送到 192.168.22.6 的私有 registry —— 版本号（tag）保持不变，覆盖式发布。
#
# 用途：改完代码后，用它把新代码做成同版本号的镜像推到 22.6，目标机 docker compose pull && up -d 即生效。
# 只处理应用镜像（ai-service / backend / frontend），中间件镜像不动。
#
# 用法：
#   ./scripts/release-push.sh                  # 三个应用镜像全量重建 + 推送，tag 保持 1.1.0
#   ./scripts/release-push.sh frontend         # 只做前端（改动只涉及前端时用这个，几十秒）
#   ./scripts/release-push.sh frontend backend
#   VERSION=1.4.0 ./scripts/release-push.sh    # 换个版本号（默认 1.1.0 = 版本不变）
#   PUSH=0 ./scripts/release-push.sh           # 只构建 + 打 tag，不推送（先本地验证）
#   BACKUP=0 ./scripts/release-push.sh         # 不做旧镜像备份（默认备份，见下）
#   DRY_RUN=1 ./scripts/release-push.sh        # 只打印将要执行的命令，不真跑
#
# 环境变量：
#   VERSION   镜像 tag，默认 1.1.0。「版本不变」= 不覆盖它，registry 上的同名 tag 会被新镜像覆盖
#   REGISTRY  registry 地址，默认 192.168.22.6:5000（HTTP，需在 /etc/docker/daemon.json 的 insecure-registries 里）
#   SERVICES  要处理的服务（空格分隔），默认 "frontend ai-service backend"；命令行参数优先
#   PUSH      1=推送（默认），0=只构建
#   BACKUP    1=推送前把 registry 上的旧 tag 另存为 <VERSION>-prev（默认 1），覆盖发布后仍可回滚
#   DRY_RUN   1=只打印不执行
#
# 回滚（BACKUP=1 时）：把 <VERSION>-prev 重新打成 <VERSION> 推回去即可，例如
#   docker tag 192.168.22.6:5000/knowledge-os/frontend:1.1.0-prev 192.168.22.6:5000/knowledge-os/frontend:1.1.0
#   docker push 192.168.22.6:5000/knowledge-os/frontend:1.1.0
set -euo pipefail

ROOT="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
cd "$ROOT"

VERSION="${VERSION:-1.1.0}"
REGISTRY="${REGISTRY:-192.168.22.6:5000}"
PUSH="${PUSH:-1}"
BACKUP="${BACKUP:-1}"
DRY_RUN="${DRY_RUN:-0}"
ALL_SERVICES=(frontend ai-service backend)
if [ "$#" -gt 0 ]; then
  SERVICES=("$@")
elif [ -n "${SERVICES:-}" ]; then
  read -r -a SERVICES <<< "$SERVICES"
else
  SERVICES=("${ALL_SERVICES[@]}")
fi
[ "${#SERVICES[@]}" -gt 0 ] || die "未指定服务；可用：${ALL_SERVICES[*]}"

export VERSION

log() { printf '[push] %s\n' "$*"; }
die() { printf '[push][ERROR] %s\n' "$*" >&2; exit 1; }
# dry-run 只打印，其余照常执行；命令里的引号交给调用方保证，这里不做二次转义
run() {
  if [ "$DRY_RUN" = "1" ]; then
    printf '[push][dry-run] %s\n' "$*"
  else
    "$@"
  fi
}
# 需要 shell 特性的命令（重定向、管道）走这里
runsh() {
  if [ "$DRY_RUN" = "1" ]; then
    printf '[push][dry-run] %s\n' "$*"
  else
    bash -c "$*"
  fi
}

# ---------------------------------------------------------------- 0. 前置检查
command -v docker >/dev/null 2>&1 || die "未检测到 docker"
docker compose version >/dev/null 2>&1 || die "未检测到 docker compose 插件"

# HTTP registry 必须在 insecure-registries 里，否则 push 会卡在 https 握手
case "$REGISTRY" in
  https://*) ;;
  *)
    # docker info 的文本段里才是 host 原文（.RegistryConfig.InsecureRegistryCIDRs 只给 CIDR，匹配不上）
    if ! docker info 2>/dev/null | grep -qF "$REGISTRY"; then
      log "提示：${REGISTRY} 不在 docker 的 insecure-registries 中，HTTP 推送可能失败；" \
        "请写进 /etc/docker/daemon.json 后 systemctl restart docker"
    fi
    ;;
esac

if [ "$PUSH" = "1" ] && [ "$DRY_RUN" != "1" ]; then
  curl -fsS -m 8 "http://${REGISTRY}/v2/" >/dev/null 2>&1 ||
    die "registry ${REGISTRY} 不可达（curl http://${REGISTRY}/v2/ 失败）"
fi

# 镜像来源可追溯：把当前 commit 打进日志
HEAD_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
log "registry=${REGISTRY} version=${VERSION} services=${SERVICES[*]} git=${HEAD_SHA}"
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  log "警告：工作区有未提交改动，这些改动会进镜像但不会体现在 commit ${HEAD_SHA} 上"
fi

# ---------------------------------------------------------------- 1~N. 构建 → 备份 → 推送 → 校验
FAILED=()
for s in "${SERVICES[@]}"; do
  LOCAL="knowledge-os/${s}:${VERSION}"
  REMOTE="${REGISTRY}/knowledge-os/${s}:${VERSION}"
  PREV="${REGISTRY}/knowledge-os/${s}:${VERSION}-prev"

  log "===== [$(date +%H:%M:%S)] build ${s} ====="
  if ! run docker compose -f docker-compose.yml build "$s"; then
    FAILED+=("${s}(build)")
    continue
  fi

  run docker tag "$LOCAL" "$REMOTE"

  # 覆盖发布前留一份旧镜像：tag 被覆盖后 registry 上就找不到上一版了
  if [ "$BACKUP" = "1" ] && [ "$PUSH" = "1" ]; then
    log "----- [$(date +%H:%M:%S)] backup ${s} -> ${VERSION}-prev"
    if runsh "docker pull ${REMOTE} >/dev/null 2>&1 && docker tag ${REMOTE} ${PREV} && docker push ${PREV} >/dev/null"; then
      log "旧镜像已备份为 ${PREV}（回滚时把它重新打成 ${VERSION} 推回即可）"
    else
      log "跳过备份：registry 上还没有 ${REMOTE}（首次发布）"
    fi
    # 上面 pull 下来的旧镜像会盖掉刚打的 REMOTE tag，重建一次指向新镜像
    run docker tag "$LOCAL" "$REMOTE"
  fi

  if [ "$PUSH" = "1" ]; then
    log "----- [$(date +%H:%M:%S)] push ${s}"
    if [ "$DRY_RUN" = "1" ]; then
      run docker push "$REMOTE"
      log "----- [$(date +%H:%M:%S)] verify ${s}：dry-run 跳过校验"
    else
      if ! PUSH_OUT="$(docker push "$REMOTE" 2>&1)"; then
        printf '%s\n' "$PUSH_OUT" >&2
        FAILED+=("${s}(push)")
        continue
      fi
      printf '%s\n' "$PUSH_OUT"
      # 校验：push 回执里的 digest 与 registry 上该 tag 的 digest 必须一致 —— 不一致说明上传不完整
      log "----- [$(date +%H:%M:%S)] verify ${s}"
      # 回执形如 "1.1.0: digest: sha256:xxx size: 2407"，取 sha256 那个字段，别取到末尾的 size
      PUSH_DIGEST="$(printf '%s\n' "$PUSH_OUT" |
        awk '{for (i = 1; i <= NF; i++) if ($i ~ /^sha256:/) print $i}' | tail -1)"
      REMOTE_DIGEST="$(curl -sI -m 10 -H 'Accept: application/vnd.docker.distribution.manifest.v2+json' \
        "http://${REGISTRY}/v2/knowledge-os/${s}/manifests/${VERSION}" |
        tr -d '\r' | awk -F': ' 'tolower($1)=="docker-content-digest" {print $2}')"
      if [ -z "$PUSH_DIGEST" ] || [ "$PUSH_DIGEST" != "$REMOTE_DIGEST" ]; then
        log "警告：digest 不一致 push=${PUSH_DIGEST:-<空>} remote=${REMOTE_DIGEST:-<空>}，请确认推送是否完整"
        FAILED+=("${s}(digest)")
      else
        log "digest 一致 ${REMOTE_DIGEST}"
      fi
    fi
  fi

  log "===== [$(date +%H:%M:%S)] ${s} 完成 ====="
done

# ---------------------------------------------------------------- 收尾
if [ "${#FAILED[@]}" -gt 0 ]; then
  die "以下环节失败：${FAILED[*]}；详见上方日志"
fi

log "全部完成：${SERVICES[*]} @ ${VERSION} 已推送到 ${REGISTRY}"
if [ "$PUSH" = "1" ] && [ "$DRY_RUN" != "1" ]; then
  log "目标机（22.6）执行下面两步才会用上新镜像 —— tag 没变，docker 不会自动更新："
  log "  docker compose pull ${SERVICES[*]}"
  log "  docker compose up -d ${SERVICES[*]}"
fi
