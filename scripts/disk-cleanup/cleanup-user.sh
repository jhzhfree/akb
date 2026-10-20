#!/usr/bin/env bash
#
# C 盘用户目录清理脚本（WSL 侧执行）
#
#   默认 dry-run，只预览不删除；加 --apply 才真正执行删除。
#   清理目标集中在同目录的 targets.conf，便于增删而无需改动脚本主体。
#
# 用法：
#   ./cleanup-user.sh                          # dry-run 预览全部默认启用目标
#   ./cleanup-user.sh --only temp              # 只处理类别为 temp 的目标
#   ./cleanup-user.sh --only temp --apply      # 真正删除 temp 类别
#   ./cleanup-user.sh --apply --enable-all     # 连默认关闭的 gradle/maven 一起删
#   ./cleanup-user.sh --skip-recent-hours 0    # 关闭「保护最近修改条目」策略
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGETS_FILE="${TARGETS_FILE:-$SCRIPT_DIR/targets.conf}"
LOG_DIR="$SCRIPT_DIR/logs"
MOUNT="${MOUNT:-/mnt/c}"

APPLY=0
ENABLE_ALL=0
ONLY=""
SKIP_RECENT_HOURS=24

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply)              APPLY=1; shift ;;
    --enable-all)         ENABLE_ALL=1; shift ;;
    --only)               ONLY="${2:-}"; shift 2 ;;
    --skip-recent-hours)  SKIP_RECENT_HOURS="${2:-24}"; shift 2 ;;
    -h|--help)            usage ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

[[ -r "$TARGETS_FILE" ]] || { echo "找不到目标清单: $TARGETS_FILE" >&2; exit 1; }
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/cleanup-$(date +%Y%m%d-%H%M%S).log"

log() { printf '%s\n' "$*" | tee -a "$LOG_FILE"; }

# C:\Users\x\Temp -> /mnt/c/Users/x/Temp
win2wsl() {
  local drive rest
  local p="${1//\\//}"
  drive="${p:0:1}"
  rest="${p:2}"
  printf '/mnt/%s%s' "${drive,,}" "$rest"
}

human() { # KiB -> 人类可读
  awk -v k="$1" 'BEGIN{
    split("KiB MiB GiB TiB",u," ");
    i=1; while(k>=1024 && i<4){k/=1024; i++}
    printf "%.2f %s", k, u[i]
  }'
}

df_used_kib() { df -Pk "$MOUNT" | awk 'NR==2{print $3}'; }

load_targets() {
  local line win cat en desc path
  while IFS= read -r line; do
    line="${line%%#*}"
    [[ -z "${line//[[:space:]]/}" ]] && continue
    IFS='|' read -r win cat en desc <<< "$line"
    win="${win%%[[:space:]]}"; win="${win##[[:space:]]}"
    cat="$(printf '%s' "${cat:-}" | tr -d '[:space:]')"
    en="$(printf '%s' "${en:-0}" | tr -d '[:space:]')"
    desc="${desc:-}"
    [[ -z "$win" || -z "$cat" ]] && continue
    [[ -n "$ONLY" && "$cat" != "$ONLY" ]] && continue
    [[ "$en" != "1" && "$ENABLE_ALL" != "1" ]] && continue
    TARGET_PATH+=("$(win2wsl "$win")")
    TARGET_CAT+=("$cat")
    TARGET_DESC+=("$desc")
  done < "$TARGETS_FILE"
}

TARGET_PATH=(); TARGET_CAT=(); TARGET_DESC=()
load_targets

if [[ ${#TARGET_PATH[@]} -eq 0 ]]; then
  log "没有匹配到任何清理目标（ONLY='$ONLY', ENABLE_ALL=$ENABLE_ALL）"
  exit 0
fi

MODE=$([[ "$APPLY" == "1" ]] && echo "APPLY（真实删除）" || echo "DRY-RUN（仅预览）")
log "=========================================================="
log " C 盘用户目录清理  |  模式: $MODE"
log " 时间:     $(date '+%F %T')"
log " 目标清单: $TARGETS_FILE"
log " 过滤类别: ${ONLY:-<全部>}   包含默认关闭项: $ENABLE_ALL"
log " 保护最近 ${SKIP_RECENT_HOURS} 小时内修改的顶层条目"
log " 日志文件: $LOG_FILE"
log "=========================================================="

USED_BEFORE=$(df_used_kib)
TOTAL=0
MISSING=0
SELECTED=()

for i in "${!TARGET_PATH[@]}"; do
  path="${TARGET_PATH[$i]}"
  if [[ ! -e "$path" ]]; then
    log "  [跳过-不存在] ${TARGET_CAT[$i]}  $path"
    MISSING=$((MISSING + 1))
    continue
  fi
  kb=$(du -sk "$path" 2>/dev/null | awk '{print $1}')
  kb=${kb:-0}
  SELECTED+=("$i")
  log "  [$(printf '%-8s' "${TARGET_CAT[$i]}")] $(human "$kb")  $path"
  [[ -n "${TARGET_DESC[$i]// /}" ]] && log "                   备注: ${TARGET_DESC[$i]}"
  TOTAL=$((TOTAL + kb))
done

log "----------------------------------------------------------"
log " 目标数量: ${#SELECTED[@]}   不存在跳过: $MISSING"
log " 预计释放: $(human "$TOTAL")"
log " 当前已用: $(human "$USED_BEFORE") ($MOUNT)"
log "----------------------------------------------------------"

if [[ "$APPLY" != "1" ]]; then
  log "这是 dry-run，未做任何删除。确认无误后加 --apply 执行。"
  exit 0
fi

log ""
log ">>> 开始删除，drvfs 上删除大量文件较慢，请勿中断 <<<"

FREED_BY_OP=0
ERRS=0

for i in "${SELECTED[@]}"; do
  path="${TARGET_PATH[$i]}"
  cat="${TARGET_CAT[$i]}"
  before=$(df_used_kib)
  log ""
  log "  -> 清理 [$cat] $path"

  if [[ "$SKIP_RECENT_HOURS" -gt 0 && -d "$path" ]]; then
    # 逐个处理顶层条目，跳过最近修改过的（避免删掉正在写入的安装包）
    kept=0; removed=0
    while IFS= read -r -d '' entry; do
      removed=$((removed + 1))
      if (( removed % 500 == 0 )); then
        log "     ... 已处理 $removed 项，累计释放 $(human "$((before - $(df_used_kib)))"))"
      fi
      if [[ -n "$(find "$entry" -newermt "-${SKIP_RECENT_HOURS} hours" -print -quit 2>/dev/null)" ]]; then
        kept=$((kept + 1))
        continue
      fi
      rm -rf -- "$entry" 2>>"$LOG_FILE" || { ERRS=$((ERRS + 1)); log "     SKIP(可能被占用): $(basename "$entry")"; }
    done < <(find "$path" -mindepth 1 -maxdepth 1 -print0 2>/dev/null)
    log "     已完成: 处理 $removed 项，保护 $kept 项"
  else
    rm -rf -- "$path" 2>>"$LOG_FILE" || { ERRS=$((ERRS + 1)); log "     SKIP(可能被占用): $path"; }
  fi

  after=$(df_used_kib)
  freed=$((before - after))
  FREED_BY_OP=$((FREED_BY_OP + freed))
  log "     本项释放: $(human "$freed")"
done

USED_AFTER=$(df_used_kib)

log ""
log "=========================================================="
log " 清理完成"
log " --------------------------------------------------------"
log " 预计释放: $(human "$TOTAL")"
log " 实际释放: $(human "$((USED_BEFORE - USED_AFTER))") ($MOUNT)"
log " 清理前:   $(human "$USED_BEFORE")"
log " 清理后:   $(human "$USED_AFTER")"
log " 遇到错误: $ERRS 项（多为被进程占用，已跳过）"
log " 日志:     $LOG_FILE"
log "=========================================================="
log ""
df -Ph "$MOUNT" | tee -a "$LOG_FILE"
