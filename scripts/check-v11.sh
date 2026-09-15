#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo '[1/29] Python syntax'
python -m compileall -q ai-service/app ai-service/tests scripts

echo '[2/29] AI pipeline smoke test'
(cd ai-service && PYTHONPATH=. python tests/test_v11_pipeline.py)

echo '[3/29] Governance / reranker / graph fallback'
(cd ai-service && PYTHONPATH=. python tests/test_v11_governance.py)

echo '[4/29] Intelligent adjudication'
(cd ai-service && PYTHONPATH=. python tests/test_adjudication.py)

echo '[5/29] AI API contract'
(cd ai-service && PYTHONPATH=. python tests/test_api_contract.py)

echo '[6/29] Performance regression'
(cd ai-service && PYTHONPATH=. python tests/test_performance_regression.py)

echo '[7/29] Elasticsearch + Milvus contract'
(cd ai-service && PYTHONPATH=. python tests/test_dual_retrieval.py)

echo '[8/29] Transactional Outbox / version contract'
(cd ai-service && PYTHONPATH=. python tests/test_outbox_index_version.py)
python scripts/check-outbox-contract.py

echo '[9/29] Blue/green schema contract'
(cd ai-service && PYTHONPATH=. python tests/test_blue_green_schema.py)

echo '[10/29] Index operations / observability contract'
python scripts/check-index-operations.py

echo '[11/29] Authoritative snapshot / integrity contract'
python scripts/check-authoritative-snapshot.py

echo '[12/29] Index operations RBAC contract'
python scripts/check-ops-rbac.py

echo '[13/29] Database / Flyway contract'
python scripts/check-db-contract.py

echo '[14/29] MyBatis mapper contract'
python scripts/check-mapper-contract.py

echo '[15/29] Performance index contract'
python scripts/check-index-contract.py

echo '[16/29] Configuration contract'
python scripts/check-config.py

echo '[17/29] Flyway ordering / XML structure'
python -c 'from pathlib import Path;import re,xml.etree.ElementTree as ET;ET.parse("backend/src/main/resources/mapper/KnowledgeMapper.xml");m=list(sorted(Path("backend/src/main/resources/db/migration").glob("V*__*.sql")));v=[int(re.match(r"V([0-9]+)__",p.name).group(1)) for p in m];assert v==sorted(set(v));print(" migrations=",[p.name for p in m])'

echo '[18/29] Java 8 forbidden API scan'
! grep -R -nE 'Map\.of\(|List\.of\(|Set\.of\(|\bvar\s+[A-Za-z_]' backend/src/main/java backend/src/test/java

echo '[19/29] Code readability / documentation contract'
python scripts/check-code-quality.py

echo '[20/29] Developer documentation contract'
python scripts/check-developer-docs.py

echo '[21/29] Frontend JavaScript syntax boundary'
node --check frontend/src/main.js
node --check frontend/src/api/knowledge.js

echo '[22/29] RC API boundary checks'
grep -q 'QueryRequest' backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeController.java
grep -q 'springdoc-openapi-ui' backend/pom.xml
grep -q 'StartupDiagnostics' backend/src/main/java/com/jh/knowledgeos/config/StartupDiagnostics.java

echo '[23/29] RC delivery files'
test -f deploy/docker-compose.prod.yml
test -f .env.production.example
test -f scripts/e2e-test.py
test -f scripts/diagnose.sh
test -f RC-FINAL.md

echo '[24/29] Compose / operational delivery'
python -c 'import yaml;[yaml.safe_load(open(p,encoding="utf-8")) for p in ["docker-compose.yml","deploy/docker-compose.prod.yml","monitoring/prometheus.yml","monitoring/alerts.yml","monitoring/grafana/provisioning/datasources/prometheus.yml","monitoring/grafana/provisioning/dashboards/llmkb.yml"]];print(" compose/monitoring yaml ok")'
test -f monitoring/grafana/dashboards/llmkb-slo.json
test -f DUAL-RETRIEVAL-ELASTICSEARCH-MILVUS.md
test -f scripts/fault-injection-indexing.sh

echo '[25/29] Maintainability refactor contract'
python3 scripts/check-maintainability-refactor.py

echo '[26/29] Elasticsearch + Milvus migration contract'
python3 scripts/check-elasticsearch-milvus-migration.py

echo '[27/29] Elasticsearch + Milvus release qualification contract'
(cd ai-service && PYTHONPATH=. python tests/test_release_qualification.py)
python3 scripts/check-release-qualification.py

echo '[28/29] Release Qualification P2 contract'
python3 scripts/check-release-qualification-p2.py

echo '[29/29] Production Readiness P3 contract'
python3 scripts/check-production-readiness-p3.py

echo 'V1.1 Release Qualification static/smoke gates passed.'
