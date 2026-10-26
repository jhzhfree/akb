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

# 1b. 资源预检。这几项的失败形态都是"装到一半才炸"，提前说出来代价最小。
avail_mem_mb="$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)"
if [ "${avail_mem_mb:-0}" -lt 8192 ]; then
  printf '[install][WARN] 可用内存约 %s MB，低于建议值 8192 MB（ES 2g + Milvus + Neo4j 1g 起步）\n' "${avail_mem_mb:-0}"
else
  log "可用内存约 ${avail_mem_mb} MB"
fi
avail_disk_gb="$(df -Pm . | awk 'NR==2 {printf "%d", $4/1024}')"
if [ "${avail_disk_gb:-0}" -lt 50 ]; then
  printf '[install][WARN] 当前目录可用磁盘约 %s GB，低于建议值 50 GB（镜像 + 数据卷）\n' "${avail_disk_gb:-0}"
else
  log "当前目录可用磁盘约 ${avail_disk_gb} GB"
fi

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
# 只匹配赋值行：.env.example 的说明注释里也提到了 CHANGE_ME，别把它当成待办项列出来。
if grep -q '^[^#]*CHANGE_ME' .env 2>/dev/null; then
  printf '[install][WARN] .env 中仍存在 CHANGE_ME 占位值，上线前必须修改：\n'
  grep -n '^[^#]*CHANGE_ME' .env | sed 's/^/           /'
  warn=1
fi
if ! grep -q '^LLM_BASE_URL=.\+' .env 2>/dev/null; then
  printf '[install][WARN] 未配置 LLM_BASE_URL，ai-service 将回退到 http://127.0.0.1:11434\n'
  warn=1
fi

# 5. 端口占用检查：真正冲突会在 up 时失败，这里提前点名，省一轮排查
port_of() { grep -E "^$1=" .env 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '"'"'" ; }
for var in PUBLIC_HTTP_PORT BACKEND_HTTP_PORT PROMETHEUS_HTTP_PORT GRAFANA_HTTP_PORT; do
  p="$(port_of "$var")"
  [ -n "$p" ] || continue
  busy=0
  if command -v ss >/dev/null 2>&1; then
    if ss -ltn 2>/dev/null | awk '{print $4}' | grep -q "[:.]$p$"; then busy=1; fi
  elif command -v netstat >/dev/null 2>&1; then
    if netstat -ltn 2>/dev/null | awk '{print $4}' | grep -q "[:.]$p$"; then busy=1; fi
  fi
  if [ "$busy" -eq 1 ]; then
    printf '[install][WARN] %s 使用的端口 %s 已被占用，请改 .env 或停掉占用方\n' "$var" "$p"
  fi
done

# 6. 编排文件校验：变量缺失、YAML 错误在这里一次暴露
if docker compose config >/dev/null 2>&1; then
  log "编排文件校验通过"
else
  printf '[install][WARN] docker compose config 未通过，请检查 .env：\n'
  docker compose config 2>&1 | head -5 | sed 's/^/           /'
  warn=1
fi

log "安装完成"
if [ "$warn" -eq 1 ]; then
  log "请先编辑 .env，再执行 ./start.sh 启动"
else
  log "执行 ./start.sh 启动产品"
fi
