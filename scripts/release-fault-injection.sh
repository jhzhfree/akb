#!/usr/bin/env bash
set -euo pipefail
# Live fault-injection matrix. Run only in a disposable qualification environment.
COMPOSE=${COMPOSE:-docker compose}
BACKEND=${BACKEND:-http://127.0.0.1:8080}
KB=${KB:-1}
WAIT=${WAIT:-8}

echo "[1] Baseline health"
$COMPOSE ps
curl -fsS "$BACKEND/actuator/health" >/dev/null

echo "[2] Elasticsearch outage -> expect outbox RETRY"
$COMPOSE stop elasticsearch
sleep "$WAIT"
curl -fsS "$BACKEND/api/knowledge/index/outbox/$KB/stats" || true
$COMPOSE start elasticsearch
sleep "$WAIT"

echo "[3] Milvus outage -> expect outbox RETRY"
$COMPOSE stop milvus
sleep "$WAIT"
curl -fsS "$BACKEND/api/knowledge/index/outbox/$KB/stats" || true
$COMPOSE start milvus
sleep "$WAIT"

echo "[4] Worker crash -> stale PROCESSING recovery"
$COMPOSE kill index-worker || true
sleep "$WAIT"
$COMPOSE up -d index-worker
sleep "$WAIT"

echo "[5] Final health / backlog"
$COMPOSE ps
curl -fsS "$BACKEND/api/knowledge/index/outbox/$KB/stats"
echo "Manual assertions: RETRY recovered, DEAD=0, lag returned to SLO, no aggregate ordering violation."
