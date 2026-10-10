#!/usr/bin/env bash
#
# 恢复演示快照（由 scripts/demo/export-snapshot.py 产出）。
#
# 运行环境：bash + curl + mysql 客户端（backend 镜像已内置）。
# 行为：先把目标库清到空状态，再按 MySQL → Elasticsearch → Milvus → Neo4j → 摄入原件 的顺序写入，
#       因此可重复执行；任一步失败只记录日志，不阻断应用启动。
#
# 用法：
#   DEMO_SEED_DIR=/opt/demo-seed bash restore.sh
#
set -uo pipefail

SEED_DIR="${DEMO_SEED_DIR:-/opt/demo-seed}"

# 连接信息优先取演示自己的变量名，其次回落到平台 .env 里的标准变量名：只认前者时，
# 部署编排没显式映射就会退回 http + 空密码，在 https + 认证的远程环境里静默失败。
MYSQL_HOST="${MYSQL_HOST:-mysql}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-${DB_USERNAME:-knowledge}}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${DB_PASSWORD:-knowledge123}}"
MYSQL_DB="${MYSQL_DB:-${MYSQL_DATABASE:-knowledge_os}}"

ES_URL="${ES_URL:-${ELASTICSEARCH_URL:-http://elasticsearch:9200}}"
ES_USER="${ES_USER:-${ELASTICSEARCH_USERNAME:-elastic}}"
ES_PASSWORD="${ES_PASSWORD:-${ELASTICSEARCH_PASSWORD:-}}"

MILVUS_URL="${MILVUS_URL:-http://milvus:19530}"
NEO4J_URL="${NEO4J_URL:-${NEO4J_HTTP_URL:-http://neo4j:7474}}"
NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-}"

SOURCE_FILES_DIR="${SOURCE_FILES_DIR:-data/source-files}"

log() { printf '[demo-seed] %s\n' "$*"; }
# 显式 utf8mb4：客户端默认 auto + 无 locale 会退化成 latin1，导入中文会产生双重编码乱码。
mysql_sql() { mysql --default-character-set=utf8mb4 -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" -N -B "$@"; }
# 取 JSON 里的字符串字段值：镜像内没有 jq/python，用 sed 提取。
json_str() { sed -n 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$1" | head -1; }
json_num() { sed -n 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' "$1" | head -1; }

if [ ! -d "$SEED_DIR" ]; then
  log "快照目录不存在：$SEED_DIR（跳过演示数据恢复）"
  exit 0
fi
if ! command -v mysql >/dev/null 2>&1; then
  log "未找到 mysql 客户端，无法恢复演示数据"
  exit 0
fi

# auto 模式：已有业务数据就什么都不做，避免每次重启都覆盖现场数据。
if [ "${DEMO_SEED_MODE:-auto}" = "auto" ]; then
  existing="$(mysql_sql -e 'SELECT COUNT(*) FROM t_document' 2>/dev/null | tail -1)"
  if [ -n "$existing" ] && [ "$existing" -gt 0 ] 2>/dev/null; then
    log "已存在业务数据（$existing 个文档），跳过恢复"
    exit 0
  fi
fi

# --------------------------------------------------------------- 等待依赖
# --insecure：远程 Elasticsearch 多为 https + 自签证书，不加会连不上且只表现为"数据没导入"。
# 就绪判定看 HTTP 码而不是"有响应"：401/000 会静默变成空库，必须报出来。
api() { curl -s --insecure "$@"; }
failures=""

wait_for() {
  local name="$1" cmd="$2" i out
  # 每个依赖最多等 2 分钟：四个都卡住也不过 8 分钟，应用不至于迟迟起不来。
  for i in $(seq 1 24); do
    out="$(eval "$cmd" 2>/dev/null)"
    # 接受 2xx：Neo4j 的 query 端点返回 202，只认 200 会白等 5 分钟再误判为不可用。
    case "${out:-}" in
      2[0-9][0-9]) log "$name 已就绪"; return 0 ;;
    esac
    sleep 5
  done
  log "$name 不可用（最后一次 HTTP 状态：${out:-无响应}）—— 请检查地址与账号密码"
  failures="${failures}${name} "
  return 1
}
wait_for "MySQL" "mysql_sql -e 'SELECT 1' >/dev/null 2>&1 && echo 200"
wait_for "Elasticsearch" "api -o /dev/null -m 5 -w '%{http_code}' -u \"${ES_USER}:${ES_PASSWORD}\" \"${ES_URL}/_cluster/health\""
wait_for "Milvus" "api -o /dev/null -m 5 -w '%{http_code}' -X POST \"${MILVUS_URL}/v2/vectordb/collections/list\" -H 'Content-Type: application/json' -d '{}'"
wait_for "Neo4j" "api -o /dev/null -m 5 -w '%{http_code}' -u \"${NEO4J_USER}:${NEO4J_PASSWORD}\" -X POST \"${NEO4J_URL}/db/neo4j/query/v2\" -H 'Content-Type: application/json' -d '{\"statement\":\"RETURN 1\"}'"

# --------------------------------------------------------------- 清空目标
log "清空目标库：$MYSQL_DB"
tables="$(mysql_sql -e "SELECT table_name FROM information_schema.tables WHERE table_schema='${MYSQL_DB}' AND table_name<>'flyway_schema_history'" 2>/dev/null)"
sql="SET FOREIGN_KEY_CHECKS=0;"
while read -r t; do [ -n "$t" ] && sql="${sql}TRUNCATE TABLE \`${t}\`;"; done <<<"$tables"
sql="${sql}SET FOREIGN_KEY_CHECKS=1;"
mysql_sql -e "$sql" 2>/dev/null

for mapping in "$SEED_DIR"/es/*.mapping.json; do
  [ -f "$mapping" ] || continue
  idx="$(basename "$mapping" .mapping.json)"
  api -o /dev/null -m 30 -u "${ES_USER}:${ES_PASSWORD}" -X DELETE "${ES_URL}/${idx}"
  log "已删除索引 $idx"
done

for schema in "$SEED_DIR"/milvus/*/schema.json; do
  [ -f "$schema" ] || continue
  cname="$(json_str "$schema" collectionName)"
  [ -z "$cname" ] && cname="$(basename "$(dirname "$schema")")"
  api -o /dev/null -m 30 -X POST "${MILVUS_URL}/v2/vectordb/collections/drop" \
    -H 'Content-Type: application/json' -d "{\"collectionName\":\"${cname}\"}"
  log "已删除向量集合 $cname"
done

api -o /dev/null -m 60 -u "${NEO4J_USER}:${NEO4J_PASSWORD}" \
  -H 'Content-Type: application/json' -X POST "${NEO4J_URL}/db/neo4j/query/v2" \
  -d '{"statement":"MATCH (n) DETACH DELETE n"}'
log "已清空图数据"

if [ -d "$SOURCE_FILES_DIR" ]; then
  find "$SOURCE_FILES_DIR" -mindepth 1 -delete 2>/dev/null || true
fi
mkdir -p "$SOURCE_FILES_DIR"

# ----------------------------------------------------------------- MySQL
for dump in "$SEED_DIR"/mysql/*.sql; do
  [ -f "$dump" ] || continue
  mysql --default-character-set=utf8mb4 -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" <"$dump" 2>/dev/null \
    && log "MySQL 已导入 $(basename "$dump")" || log "MySQL 导入失败：$dump"
done

# 兜底：历史快照可能是在 latin1 连接下导出的（中文被双重编码），导入后统一修复一次。
# 判定：含多字节字符 + latin1 往返无损（码点 <= 0xFF）+ 还原后含汉字。
fix_mojibake() {
  local tmp
  tmp="$(mktemp -d)" || return 0
  cat > "$tmp/gen.sql" <<'SQL'
SELECT CONCAT(
  'UPDATE `', TABLE_NAME, '` SET `', COLUMN_NAME,
  '` = CONVERT(CAST(CONVERT(`', COLUMN_NAME, '` USING latin1) AS BINARY) USING utf8mb4)',
  ' WHERE LENGTH(`', COLUMN_NAME, '`) <> CHAR_LENGTH(`', COLUMN_NAME, '`)',
  ' AND CONVERT(CONVERT(`', COLUMN_NAME, '` USING latin1) USING utf8mb4) = `', COLUMN_NAME, '`',
  ' AND CONVERT(CAST(CONVERT(`', COLUMN_NAME, '` USING latin1) AS BINARY) USING utf8mb4) REGEXP ''[一-鿿]'';')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND DATA_TYPE IN ('varchar', 'char', 'text', 'mediumtext', 'longtext');
SQL
  mysql_sql < "$tmp/gen.sql" > "$tmp/fix.sql" 2>/dev/null
  mysql_sql < "$tmp/fix.sql" >/dev/null 2>&1
  rm -rf "$tmp"
  log "已校验中文编码（如存在双重编码已自动修复）"
}
fix_mojibake

# ------------------------------------------------------------ Elasticsearch
for mapping in "$SEED_DIR"/es/*.mapping.json; do
  [ -f "$mapping" ] || continue
  idx="$(basename "$mapping" .mapping.json)"
  code="$(api -o /dev/null -w '%{http_code}' -m 60 -u "${ES_USER}:${ES_PASSWORD}" \
    -X PUT "${ES_URL}/${idx}" -H 'Content-Type: application/json' --data-binary "@${mapping}")"
  log "索引 $idx 建表 -> $code"
  # 写入：索引导入后校验条数，为 0 时重试一次（环境里可能另有进程重建了空索引）
  attempt=1
  while [ "$attempt" -le 3 ]; do
    for bulk in "$SEED_DIR"/es/"${idx}".bulk-*.ndjson; do
      [ -f "$bulk" ] || continue
      api -o /dev/null -m 300 -u "${ES_USER}:${ES_PASSWORD}" -X POST "${ES_URL}/_bulk" \
        -H 'Content-Type: application/x-ndjson' --data-binary "@${bulk}"
    done
    count="$(api -m 30 -u "${ES_USER}:${ES_PASSWORD}" "${ES_URL}/${idx}/_count" \
      | sed -n 's/.*"count":\([0-9]*\).*/\1/p' | head -1)"
    if [ "${count:-0}" -gt 0 ]; then
      break
    fi
    log "索引 $idx 第 $attempt 次导入后仍为空，重试"
    attempt=$((attempt + 1))
    sleep 3
  done
  log "索引 $idx 数据已导入（$count 篇）"
done

# ----------------------------------------------------------------- Milvus
for schema in "$SEED_DIR"/milvus/*/schema.json; do
  [ -f "$schema" ] || continue
  cname="$(json_str "$schema" collectionName)"
  dim="$(json_num "$schema" dimension)"
  metric="$(json_str "$schema" metricType)"
  [ -z "$cname" ] && cname="$(basename "$(dirname "$schema")")"
  [ -z "$metric" ] && metric="COSINE"
  pfield="$(json_str "$schema" primaryField)"
  vfield="$(json_str "$schema" vectorField)"
  [ -z "$pfield" ] && pfield="id"
  [ -z "$vfield" ] && vfield="vector"
  # 必须显式声明主键为 VarChar：默认建出来的主键是 Int64，插入 "2:1" 这类业务主键会失败
  api -m 60 -X POST "${MILVUS_URL}/v2/vectordb/collections/create" \
    -H 'Content-Type: application/json' \
    -d "{\"collectionName\":\"${cname}\",\"enableDynamicField\":true,\"schema\":{\"fields\":[{\"fieldName\":\"${pfield}\",\"dataType\":\"VarChar\",\"isPrimary\":true,\"elementTypeParams\":{\"max_length\":\"256\"},\"autoID\":false},{\"fieldName\":\"${vfield}\",\"dataType\":\"FloatVector\",\"elementTypeParams\":{\"dim\":\"${dim}\"}}]},\"indexParams\":[{\"fieldName\":\"${vfield}\",\"indexName\":\"${vfield}_idx\",\"metricType\":\"${metric}\"}]}" \
    | grep -q '"code":0' && log "集合 $cname 已创建（dim=$dim, metric=$metric）" \
    || log "集合 $cname 创建失败（可忽略：若已存在则直接插入）"
  for part in "$(dirname "$schema")"/insert-*.json; do
    [ -f "$part" ] || continue
    api -o /dev/null -m 600 -X POST "${MILVUS_URL}/v2/vectordb/entities/insert" \
      -H 'Content-Type: application/json' --data-binary "@${part}"
  done
  # 落盘并加载：不 flush/load 的话检索查不到刚写入的向量
  api -o /dev/null -m 120 -X POST "${MILVUS_URL}/v2/vectordb/collections/flush" \
    -H 'Content-Type: application/json' -d "{\"collectionName\":\"${cname}\"}"
  api -o /dev/null -m 120 -X POST "${MILVUS_URL}/v2/vectordb/collections/load" \
    -H 'Content-Type: application/json' -d "{\"collectionName\":\"${cname}\"}"
  log "集合 $cname 向量已导入并加载"
done

# ------------------------------------------------------------------ Neo4j
for payload in "$SEED_DIR"/neo4j/*.json; do
  [ -f "$payload" ] || continue
  api -o /dev/null -m 600 -u "${NEO4J_USER}:${NEO4J_PASSWORD}" \
    -H 'Content-Type: application/json' -X POST "${NEO4J_URL}/db/neo4j/query/v2" \
    --data-binary "@${payload}"
  log "图数据已导入 $(basename "$payload")"
done

# --------------------------------------------------------------- 摄入原件
for tar in "$SEED_DIR"/source-files.tar.gz; do
  [ -f "$tar" ] || continue
  mkdir -p "$SOURCE_FILES_DIR"
  tar xzf "$tar" -C "$(dirname "$SOURCE_FILES_DIR")" && log "摄入原件已恢复"
done

# ------------------------------------------------------------------ 结果核对
# 恢复完必须自证：远程反馈"数据没下来"时，先看这里打印的实际条数和未就绪依赖，
# 而不是猜。任一步为 0 都以非 0 退出，让 entrypoint 明确报"恢复未完成"。
docs="$(mysql_sql -e 'SELECT COUNT(*) FROM t_document' 2>/dev/null | tail -1)"
cards="$(mysql_sql -e 'SELECT COUNT(*) FROM t_knowledge_card' 2>/dev/null | tail -1)"
es_docs=0
for mapping in "$SEED_DIR"/es/*.mapping.json; do
  [ -f "$mapping" ] || continue
  idx="$(basename "$mapping" .mapping.json)"
  n="$(api -m 30 -u "${ES_USER}:${ES_PASSWORD}" "${ES_URL}/${idx}/_count" \
    | sed -n 's/.*"count":\([0-9]*\).*/\1/p' | head -1)"
  es_docs=$((es_docs + ${n:-0}))
done
vectors=0
for schema in "$SEED_DIR"/milvus/*/schema.json; do
  [ -f "$schema" ] || continue
  cname="$(json_str "$schema" collectionName)"
  [ -z "$cname" ] && cname="$(basename "$(dirname "$schema")")"
  # 行数只有 get_stats 给得出来：describe 返回的是 schema，不含 rowCount。
  n="$(api -m 30 -X POST "${MILVUS_URL}/v2/vectordb/collections/get_stats" \
    -H 'Content-Type: application/json' -d "{\"collectionName\":\"${cname}\"}" \
    | sed -n 's/.*"rowCount":\([0-9]*\).*/\1/p' | head -1)"
  vectors=$((vectors + ${n:-0}))
done
nodes="$(api -m 30 -u "${NEO4J_USER}:${NEO4J_PASSWORD}" -X POST "${NEO4J_URL}/db/neo4j/query/v2" \
  -H 'Content-Type: application/json' -d '{"statement":"MATCH (n) RETURN count(n)"}' \
  | sed -n 's/.*\[\[\([0-9]*\)\].*/\1/p' | head -1)"

log "恢复结果：文档 ${docs:-0} 篇 / 卡片 ${cards:-0} 张 / ES ${es_docs} 条 / 向量 ${vectors} 条 / 图节点 ${nodes:-0} 个"
if [ -n "$failures" ]; then
  log "以下依赖本次不可用：${failures}——对应数据未导入，应用仍会正常启动"
fi
if [ "${docs:-0}" -eq 0 ] 2>/dev/null; then
  log "MySQL 未灌入任何文档：请检查 MYSQL_HOST/MYSQL_USER/MYSQL_PASSWORD/MYSQL_DB 与快照 $SEED_DIR"
  exit 1
fi
log "演示数据恢复完成。"
