#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo '[1/44] Python syntax'
python -m compileall -q ai-service/app ai-service/tests scripts

echo '[2/44] AI pipeline smoke test'
(cd ai-service && PYTHONPATH=. python tests/test_v11_pipeline.py)

echo '[3/44] Governance / reranker / graph fallback'
(cd ai-service && PYTHONPATH=. python tests/test_v11_governance.py)

echo '[4/44] Intelligent adjudication'
(cd ai-service && PYTHONPATH=. python tests/test_adjudication.py)

echo '[5/44] AI API contract'
(cd ai-service && PYTHONPATH=. python tests/test_api_contract.py)

echo '[6/44] Performance regression'
(cd ai-service && PYTHONPATH=. python tests/test_performance_regression.py)

echo '[7/44] Elasticsearch + Milvus contract'
(cd ai-service && PYTHONPATH=. python tests/test_dual_retrieval.py)

echo '[8/44] Transactional Outbox / version contract'
(cd ai-service && PYTHONPATH=. python tests/test_outbox_index_version.py)
python scripts/check-outbox-contract.py

echo '[9/44] Blue/green schema contract'
(cd ai-service && PYTHONPATH=. python tests/test_blue_green_schema.py)

echo '[10/44] Index operations / observability contract'
python scripts/check-index-operations.py

echo '[11/44] Authoritative snapshot / integrity contract'
python scripts/check-authoritative-snapshot.py

echo '[12/44] Index operations RBAC contract'
python scripts/check-ops-rbac.py

echo '[13/44] Database / Flyway contract'
python scripts/check-db-contract.py

echo '[14/44] MyBatis mapper contract'
python scripts/check-mapper-contract.py

echo '[15/44] Performance index contract'
python scripts/check-index-contract.py

echo '[16/44] Configuration contract'
python scripts/check-config.py

echo '[17/44] Flyway ordering / XML structure'
python -c 'from pathlib import Path;import re,xml.etree.ElementTree as ET;ET.parse("backend/src/main/resources/mapper/KnowledgeMapper.xml");m=list(Path("backend/src/main/resources/db/migration").glob("V*__*.sql"));m.sort(key=lambda p:int(re.match(r"V([0-9]+)__",p.name).group(1)));v=[int(re.match(r"V([0-9]+)__",p.name).group(1)) for p in m];assert v==sorted(set(v));print(" migrations=",[p.name for p in m])'

echo '[18/44] Java 8 forbidden API scan'
! grep -R -nE 'Map\.of\(|List\.of\(|Set\.of\(|\bvar\s+[A-Za-z_]' backend/src/main/java backend/src/test/java

echo '[19/44] Code readability / documentation contract'
python scripts/check-code-quality.py

echo '[20/44] Developer documentation contract'
python scripts/check-developer-docs.py

echo '[21/44] Frontend TypeScript boundary'
python3 scripts/check-frontend-typescript.py
true # covered by check-frontend-typescript.py

echo '[22/44] RC API boundary checks'
grep -q 'QueryRequest' backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeQueryController.java
grep -q 'ApiResponse<QueryResponse>' backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeQueryController.java
grep -q 'springdoc-openapi-ui' backend/pom.xml
grep -q 'StartupDiagnostics' backend/src/main/java/com/jh/knowledgeos/config/StartupDiagnostics.java

echo '[23/44] RC delivery files'
test -f deploy/docker-compose.prod.yml
test -f .env.production.example
test -f scripts/e2e-test.py
test -f scripts/diagnose.sh
test -f docs/release/RC-CHECKLIST.md

echo '[24/44] Compose / operational delivery'
python -c 'import yaml;[yaml.safe_load(open(p,encoding="utf-8")) for p in ["docker-compose.yml","deploy/docker-compose.prod.yml","monitoring/prometheus.yml","monitoring/alerts.yml","monitoring/grafana/provisioning/datasources/prometheus.yml","monitoring/grafana/provisioning/dashboards/llmkb.yml"]];print(" compose/monitoring yaml ok")'
test -f monitoring/grafana/dashboards/llmkb-slo.json
test -f docs/design/DUAL-RETRIEVAL-ELASTICSEARCH-MILVUS.md
test -f scripts/fault-injection-indexing.sh

echo '[25/44] Maintainability refactor contract'
python3 scripts/check-maintainability-refactor.py

echo '[26/44] Elasticsearch + Milvus migration contract'
python3 scripts/check-elasticsearch-milvus-migration.py

echo '[27/44] Elasticsearch + Milvus release qualification contract'
(cd ai-service && PYTHONPATH=. python tests/test_release_qualification.py)
python3 scripts/check-release-qualification.py

echo '[28/44] Release Qualification P2 contract'
python3 scripts/check-release-qualification-p2.py

echo '[29/44] Production Readiness P3 contract'
python3 scripts/check-production-readiness-p3.py

echo '[30/44] Prototype TypeScript + ingestion observability round'
python3 scripts/check-prototype-ts-ingestion-round.py

echo '[31/44] Knowledge Detail Workspace contract'
python3 scripts/check-knowledge-detail-workspace.py

echo '[32/44] Query History + Trace Workspace contract'
python3 scripts/check-query-history-trace-round.py

echo '[33/44] Knowledge Version + Evidence Traceability contract'
python3 scripts/check-version-evidence-round.py

echo '[34/44] Evidence Viewer + cross navigation contract'
python3 scripts/check-evidence-viewer-navigation-round.py

echo '[35/44] Query Trace visualization + candidate navigation'
python3 scripts/check-query-trace-visualization-round.py

echo '[36/44] Query Trace deep observability'
python3 scripts/check-query-trace-deep-observability-round.py

echo '[37/44] Authoritative ACL persistence + retrieval contract'
python3 scripts/check-authoritative-acl-round.py

echo '[38/44] ACL Phase 2 identity + leakage contract'
python3 scripts/check-acl-phase2.py

echo '[39/44] ACL Phase 3 governance + propagation contract'
python3 scripts/check-acl-phase3-governance.py

echo '[40/44] ACL Phase 4 engineering closure'
python3 scripts/check-acl-phase4-engineering-closure.py

echo '[41/44] ACL Phase 5 rebuild orchestrator + activation barrier'
python3 scripts/check-acl-phase5-orchestrator.py

echo 'V1.1 Release Qualification static/smoke gates passed.'

echo '[42/44] ACL Phase 6 machine leakage qualification'
python3 scripts/check-acl-phase6-machine-qualification.py

echo "[43/44] ACL Phase 7 authoritative manifest + generation barrier"
python3 scripts/check-acl-phase7-manifest-generation.py


echo "[44/44] ACL Phase 8 Neo4j generation + three-engine barrier"
python3 scripts/check-acl-phase8-neo4j-generation.py

echo "[45/45] ACL Phase 11 cross-source non-broadening ACL"
python3 scripts/check-acl-phase11-cross-source.py

echo "[46/51] ACL Phase 9 authoritative graph generation"
python3 scripts/check-acl-phase9-graph-generation.py

echo "[47/51] ACL Phase 10 graph ACL + GraphRAG"
python3 scripts/check-acl-phase10.py

echo "[48/51] ACL Phase 12 evidence-scoped facets"
python3 scripts/check-acl-phase12-facets.py

echo "[49/51] ACL Phase 13 immutable evidence citations"
python3 scripts/check-acl-phase13-evidence-citations.py

echo "[50/51] ACL Phase 15/16 answer evidence gates"
python3 scripts/check-acl-phase15-answer-evidence.py
python3 scripts/check-acl-phase16-structured-answer.py

echo "[51/51] ACL Phase 17 unified answer qualification"
python3 scripts/check-acl-phase17-answer-qualification.py
(cd ai-service && PYTHONPATH=. python tests/test_phase17_answer_qualification.py)

echo "[52/52] ACL Phase 18 answer-quality operations"
python3 scripts/check-acl-phase18-answer-quality-operations.py
python3 scripts/check-phase19-okf-v02.py

echo "[53/53] Frontend secure-context API contract"
python3 scripts/check-frontend-secure-context.py
