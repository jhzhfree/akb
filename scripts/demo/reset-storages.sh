#!/usr/bin/env bash
#
# 清空演示环境的全部业务数据，只保留库表结构与一个可登录的管理员账号。
#
# 清理范围：MySQL 业务表（保留 flyway_schema_history）、Elasticsearch 检索索引、
#           Milvus 向量集合、Neo4j 图、Redis 缓存、摄入原件目录。
# 清理后写入：默认知识库（KB_DEFAULT）、管理员账号（默认口令 1qaz@WSX）及其 ACL 主体。
#
# 用法：
#   scripts/demo/reset-storages.sh                    # 按默认开发环境清理
#   MYSQL_CONTAINER=xxx ES_PASSWORD=yyy scripts/demo/reset-storages.sh
#
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-kos-dev-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MYSQL_DB="${MYSQL_DB:-knowledge_os}"

REDIS_CONTAINER="${REDIS_CONTAINER:-kos-dev-redis}"

ES_URL="${ES_URL:-http://127.0.0.1:9200}"
ES_USER="${ES_USER:-elastic}"
ES_PASSWORD="${ES_PASSWORD:-eucwdo0h4IlxoHdErDsa}"
ES_INDEX_PREFIX="${ES_INDEX_PREFIX:-llmkb_}"

MILVUS_URL="${MILVUS_URL:-http://127.0.0.1:19530}"

NEO4J_URL="${NEO4J_URL:-http://127.0.0.1:7474}"
NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-knowledge123}"

SOURCE_FILES_DIR="${SOURCE_FILES_DIR:-data/source-files}"

ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-1qaz@WSX}"
ADMIN_DISPLAY_NAME="${ADMIN_DISPLAY_NAME:-系统管理员}"

log() { printf '[reset] %s\n' "$*"; }

# ------------------------------------------------------------------ helpers
mysql_exec() {
  # 必须显式指定字符集：客户端默认为 auto，容器无 locale 时会退化成 latin1，
  # 中文会被按 latin1 解码后再编码成 utf8mb4 存储，形成双重编码乱码。
  docker exec -i "$MYSQL_CONTAINER" mysql --default-character-set=utf8mb4 \
    -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" -N -B "$@" 2>/dev/null
}

# PBKDF2-HMAC-SHA256，与后端 PasswordHasher 完全同构：pbkdf2_sha256$iter$salt$hash
hash_password() {
  python3 - "$1" <<'PY'
import base64, hashlib, secrets, sys
salt = secrets.token_bytes(16)
dk = hashlib.pbkdf2_hmac("sha256", sys.argv[1].encode("utf-8"), salt, 120000, dklen=32)
print("pbkdf2_sha256$120000$%s$%s" % (base64.b64encode(salt).decode(), base64.b64encode(dk).decode()))
PY
}

# -------------------------------------------------------------------- MySQL
log "MySQL：清空 $MYSQL_DB 的业务表（保留 flyway_schema_history）"
tables="$(mysql_exec -e "SELECT table_name FROM information_schema.tables WHERE table_schema='${MYSQL_DB}' AND table_name<>'flyway_schema_history'")"
if [ -z "${tables// /}" ]; then
  log "  未取到表清单，跳过（检查 MYSQL_CONTAINER / 账号是否正确）"
else
  sql="SET FOREIGN_KEY_CHECKS=0;"
  while read -r t; do
    [ -n "$t" ] && sql="${sql}TRUNCATE TABLE \`${t}\`;"
  done <<<"$tables"
  sql="${sql}SET FOREIGN_KEY_CHECKS=1;"
  mysql_exec -e "$sql"
  log "  已清空 $(printf '%s\n' "$tables" | grep -c .) 张表"
fi

admin_hash="$(hash_password "$ADMIN_PASSWORD")"
mysql_exec -e "INSERT IGNORE INTO t_knowledge_base(id,base_code,name)VALUES(1,'KB_DEFAULT','默认知识库');
INSERT INTO t_user(user_code,username,display_name,email,password_hash,role,status)
VALUES('USR_ADMIN_SEED','${ADMIN_USERNAME}','${ADMIN_DISPLAY_NAME}','admin@demo.local','${admin_hash}','ADMIN','ACTIVE');
INSERT IGNORE INTO t_acl_subject(subject_code,display_name)VALUES('USR_ADMIN_SEED','${ADMIN_DISPLAY_NAME}');"
log "  已写入默认知识库与管理员 ${ADMIN_USERNAME}（口令 ${ADMIN_PASSWORD}）"

# ------------------------------------------------------------------- Redis
if docker ps --format '{{.Names}}' | grep -qx "$REDIS_CONTAINER"; then
  docker exec "$REDIS_CONTAINER" redis-cli FLUSHALL >/dev/null 2>&1 && log "Redis：已清空 $REDIS_CONTAINER"
else
  log "Redis：容器 $REDIS_CONTAINER 不存在，跳过"
fi

# ----------------------------------------------------------- Elasticsearch
log "Elasticsearch：删除 ${ES_INDEX_PREFIX}* 索引"
indices="$(curl -s -m 15 -u "${ES_USER}:${ES_PASSWORD}" "${ES_URL}/_cat/indices?h=index" || true)"
removed=0
while read -r idx; do
  [ -z "$idx" ] && continue
  case "$idx" in
    "${ES_INDEX_PREFIX}"*)
      code="$(curl -s -o /dev/null -w '%{http_code}' -m 20 -u "${ES_USER}:${ES_PASSWORD}" -X DELETE "${ES_URL}/${idx}")"
      log "  删除索引 ${idx} -> ${code}"
      removed=$((removed + 1))
      ;;
  esac
done <<<"$indices"
[ "$removed" -eq 0 ] && log "  无匹配索引"

# ------------------------------------------------------------------ Milvus
log "Milvus：删除向量集合"
collections="$(curl -s -m 15 -X POST "${MILVUS_URL}/v2/vectordb/collections/list" -H 'Content-Type: application/json' -d '{}' || true)"
names="$(printf '%s' "$collections" | python3 -c 'import json,sys
try:
    print("\n".join(json.load(sys.stdin).get("data") or []))
except Exception:
    print("")' 2>/dev/null || true)"
if [ -z "${names// /}" ]; then
  log "  无集合或接口不可用"
else
  while read -r c; do
    [ -z "$c" ] && continue
    curl -s -o /dev/null -m 30 -X POST "${MILVUS_URL}/v2/vectordb/collections/drop" \
      -H 'Content-Type: application/json' -d "{\"collectionName\":\"${c}\"}"
    log "  已删除集合 ${c}"
  done <<<"$names"
fi

# ------------------------------------------------------------------- Neo4j
log "Neo4j：删除全部节点与关系"
curl -s -o /dev/null -m 60 -u "${NEO4J_USER}:${NEO4J_PASSWORD}" \
  -H 'Content-Type: application/json' -X POST "${NEO4J_URL}/db/neo4j/query/v2" \
  -d '{"statement":"MATCH (n) DETACH DELETE n"}'
log "  已清空图数据"

# -------------------------------------------------------------- 摄入原件
if [ -d "$SOURCE_FILES_DIR" ]; then
  before="$(find "$SOURCE_FILES_DIR" -type f | wc -l)"
  find "$SOURCE_FILES_DIR" -mindepth 1 -delete 2>/dev/null || true
  log "摄入原件：已删除 ${SOURCE_FILES_DIR} 下 ${before} 个文件"
else
  log "摄入原件：目录不存在，跳过（${SOURCE_FILES_DIR}）"
fi

log "清理完成。"
