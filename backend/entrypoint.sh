#!/usr/bin/env bash
#
# 后端容器入口：可选地灌入演示数据，再启动应用。
#
# DEMO_SEED=auto（默认）：业务表为空时才恢复快照，已有数据不动；
# DEMO_SEED=true        ：总是恢复（覆盖当前业务数据）；
# DEMO_SEED=false       ：不恢复，保持空系统。
#
# 恢复失败只打印日志，不影响应用启动——演示数据是锦上添花，不该让平台起不来。
#
set -uo pipefail

mode="${DEMO_SEED:-auto}"

if [ "$mode" != "false" ] && [ -f /opt/demo-seed/restore.sh ]; then
  echo "[entrypoint] DEMO_SEED=$mode，检查演示数据"
  if DEMO_SEED_MODE="$mode" bash /opt/demo-seed/restore.sh; then
    echo "[entrypoint] 演示数据已就绪"
  else
    echo "[entrypoint] 演示数据恢复未完成，继续启动应用（不影响正常使用）"
    echo "[entrypoint] 排查：确认 MYSQL_HOST/MYSQL_USER/MYSQL_PASSWORD/MYSQL_DB、ES_URL/ES_USER/ES_PASSWORD、"
    echo "[entrypoint]       MILVUS_URL、NEO4J_URL/NEO4J_USER/NEO4J_PASSWORD 与实际环境一致；"
    echo "[entrypoint]       也接受平台变量 DB_USERNAME/DB_PASSWORD、ELASTICSEARCH_URL/ELASTICSEARCH_USERNAME/ELASTICSEARCH_PASSWORD。"
  fi
else
  echo "[entrypoint] 未启用演示数据（DEMO_SEED=$mode 或无快照）"
fi

exec java -XX:+ExitOnOutOfMemoryError -jar /app/app.jar
