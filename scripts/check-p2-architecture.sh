#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KM="$ROOT/backend/src/main/java/com/jh/knowledgeos/mapper/KnowledgeMapper.java"
for method in insertCard insertClaim listCards listClaims insertTrace getTrace insertAnswerQualification batchInsertEvidence; do
  if grep -q " $method(" "$KM"; then echo "P2 architecture violation: $method remains in KnowledgeMapper" >&2; exit 1; fi
done
for file in CardClaimMapper TraceMapper EvidenceMapper; do
  test -f "$ROOT/backend/src/main/java/com/jh/knowledgeos/mapper/$file.java"
  test -f "$ROOT/backend/src/main/resources/mapper/$file.xml"
done
for controller in KnowledgeSettingsController KnowledgeLifecycleController EntityGovernanceController; do
  test -f "$ROOT/backend/src/main/java/com/jh/knowledgeos/controller/$controller.java"
done
echo "P2 architecture checks passed"

# P2.13-P2.15 closure guards: prevent God mapper/controller/page regression.
[ "$(wc -l < backend/src/main/java/com/jh/knowledgeos/mapper/KnowledgeMapper.java)" -le 30 ] || { echo "KnowledgeMapper compatibility facade grew beyond 30 lines"; exit 1; }
[ "$(wc -l < backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeController.java)" -le 30 ] || { echo "KnowledgeController compatibility marker grew beyond 30 lines"; exit 1; }
[ "$(wc -l < frontend/src/components/ExternalServiceLab.vue)" -le 550 ] || { echo "ExternalServiceLab.vue grew beyond 550 lines"; exit 1; }
