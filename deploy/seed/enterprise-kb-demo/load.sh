#!/usr/bin/env bash
# Load the five scenario knowledge bases into a fresh deployment.
#
# Prerequisites:
#   * MySQL reachable and the schema created (start the backend once, Flyway creates it)
#   * The backend stopped or idle: rows are inserted with their original ids
#   * SOURCE_DIR points at the backend's uploaded-file directory
#
# Usage:
#   MYSQL_CONTAINER=kos-dev-mysql SOURCE_DIR=../../backend/data/source-files ./load.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-kos-dev-mysql}"
MYSQL_USER="${MYSQL_USER:-knowledge}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-knowledge123}"
MYSQL_DATABASE="${MYSQL_DATABASE:-knowledge_os}"
SOURCE_DIR="${SOURCE_DIR:-$(cd "$HERE/../../../backend/data/source-files" && pwd)}"
KBS=(6 7 8 9 10)

echo "== 检查目标知识库 id 是否空闲"
for kb in "${KBS[@]}"; do
  existing=$(docker exec "$MYSQL_CONTAINER" mysql -N -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
    -e "select count(*) from ${MYSQL_DATABASE}.t_knowledge_base where id=${kb}" 2>/dev/null | tr -d ' ')
  if [ "$existing" != "0" ]; then
    echo "知识库 id ${kb} 已存在（${existing} 条），先清理或改用空库后再装载" >&2
    exit 1
  fi
done

echo "== 导入知识数据"
docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
  --default-character-set=utf8mb4 "$MYSQL_DATABASE" < "$HERE/knowledge_data.sql"

echo "== 还原上传的源文件 -> $SOURCE_DIR"
mkdir -p "$SOURCE_DIR"
cp -r "$HERE"/source-files/* "$SOURCE_DIR/"

cat <<'EOM'

== 数据已装载，索引仍需重建

索引（Elasticsearch / Milvus / Neo4j）是可重建视图，没有随镜像复制。
启动 backend 与 ai-service 后，对每个知识库做一次蓝绿重建并激活：

  VERSION=v1
  for kb in 6 7 8 9 10; do
    RELEASE=$(curl -s -X POST "http://127.0.0.1:8080/api/knowledge/index/schema/${kb}/rebuild?schemaVersion=${VERSION}" \
      -H "Authorization: Bearer ${TOKEN}" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['releaseId'])")
    curl -s -X POST "http://127.0.0.1:8080/api/knowledge/index/schema/${kb}/activate/${RELEASE}" \
      -H "Authorization: Bearer ${TOKEN}"
  done

然后用 dataset/enterprise_kb_validation_5sets/validation_cases.csv 的 5 个问题回归一次。
EOM
