#!/usr/bin/env bash
# 修复 MySQL 中的中文双重编码（mojibake）。
#
# 成因：mysql 客户端 default-character-set=auto，容器/最小镜像内没有 locale 时会退化成
# latin1，连接字符集为 latin1 时写入/导出的中文 UTF-8 字节会被逐字节当成 latin1 字符再
# 编码成 utf8mb4 落库（"默认知识库" -> "é»˜è®¤çŸ¥è¯†åº“"），读取时同样按 latin1 呈现。
#
# 判定：字符串含多字节字符 + latin1 往返无损（每个字符码点 <= 0xFF）+ 还原后含汉字。
# 修复：CONVERT(CAST(CONVERT(col USING latin1) AS BINARY) USING utf8mb4)。
#
# 用法：既可在本机通过 MYSQL_CONTAINER 走 docker exec，也可在容器内直连 MySQL。
set -uo pipefail

MYSQL_HOST="${MYSQL_HOST:-mysql}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MYSQL_DB="${MYSQL_DB:-knowledge_os}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-}"
CHARSET="utf8mb4"

log() { printf '[mojibake] %s\n' "$*"; }

mysql_cli() {
  if [ -n "$MYSQL_CONTAINER" ] && command -v docker >/dev/null 2>&1 \
      && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$MYSQL_CONTAINER"; then
    docker exec -i "$MYSQL_CONTAINER" mysql --default-character-set="$CHARSET" \
      -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" -N -B "$@"
  else
    mysql --default-character-set="$CHARSET" -h"$MYSQL_HOST" -P"$MYSQL_PORT" \
      -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" -N -B "$@"
  fi
}

if ! mysql_cli -e 'SELECT 1' >/dev/null 2>&1; then
  log "无法连接 MySQL，跳过编码修复"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 逐列生成「统计受影响行」的语句
cat > "$TMP/gen-detect.sql" <<'SQL'
SELECT CONCAT(
  'SELECT ''', TABLE_NAME, '.', COLUMN_NAME, ''' AS col, COUNT(*) AS n FROM `', TABLE_NAME,
  '` WHERE LENGTH(`', COLUMN_NAME, '`) <> CHAR_LENGTH(`', COLUMN_NAME, '`)',
  ' AND CONVERT(CONVERT(`', COLUMN_NAME, '` USING latin1) USING utf8mb4) = `', COLUMN_NAME, '`',
  ' AND CONVERT(CAST(CONVERT(`', COLUMN_NAME, '` USING latin1) AS BINARY) USING utf8mb4) REGEXP ''[一-鿿]''',
  ' HAVING n > 0;')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND DATA_TYPE IN ('varchar', 'char', 'text', 'mediumtext', 'longtext')
ORDER BY TABLE_NAME, COLUMN_NAME;
SQL

# 逐列生成修复语句（条件与统计完全一致）
cat > "$TMP/gen-fix.sql" <<'SQL'
SELECT CONCAT(
  'UPDATE `', TABLE_NAME, '` SET `', COLUMN_NAME,
  '` = CONVERT(CAST(CONVERT(`', COLUMN_NAME, '` USING latin1) AS BINARY) USING utf8mb4)',
  ' WHERE LENGTH(`', COLUMN_NAME, '`) <> CHAR_LENGTH(`', COLUMN_NAME, '`)',
  ' AND CONVERT(CONVERT(`', COLUMN_NAME, '` USING latin1) USING utf8mb4) = `', COLUMN_NAME, '`',
  ' AND CONVERT(CAST(CONVERT(`', COLUMN_NAME, '` USING latin1) AS BINARY) USING utf8mb4) REGEXP ''[一-鿿]'';')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND DATA_TYPE IN ('varchar', 'char', 'text', 'mediumtext', 'longtext')
ORDER BY TABLE_NAME, COLUMN_NAME;
SQL

mysql_cli < "$TMP/gen-detect.sql" > "$TMP/detect.sql" 2>/dev/null
mysql_cli < "$TMP/gen-fix.sql" > "$TMP/fix.sql" 2>/dev/null

affected="$(mysql_cli < "$TMP/detect.sql" 2>/dev/null)"
if [ -z "$affected" ]; then
  log "未发现双重编码数据，无需修复"
  exit 0
fi

log "发现双重编码数据："
printf '%s\n' "$affected" | sed 's/^/  /'

mysql_cli < "$TMP/fix.sql" >/dev/null 2>&1
remaining="$(mysql_cli < "$TMP/detect.sql" 2>/dev/null)"
if [ -z "$remaining" ]; then
  log "修复完成，复核无残留"
else
  log "修复后仍有残留："
  printf '%s\n' "$remaining" | sed 's/^/  /'
  exit 1
fi
