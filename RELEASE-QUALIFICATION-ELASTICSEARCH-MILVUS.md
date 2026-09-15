# V1.1 Elasticsearch + Milvus Release Qualification

## Qualification baseline
- Elasticsearch 9.5.3
- Milvus 3.0.1
- MySQL authoritative state + Transactional Outbox
- Neo4j graph retrieval remains unchanged

## Release gates
P0: all 27 static/smoke contracts pass; Flyway V1-V7 ordered; no legacy production OpenSearch/Qdrant references; Java 8 forbidden API scan passes.

P1 live: real Elasticsearch and Milvus start successfully; mappings/collection schema can be created; bulk/upsert count matches source; ACL/status filters execute before Top-K; blue/green alias activation and rollback succeed.

P2 performance: execute `scripts/release-qualification-live.py` first at 1k, then 10k/100k. Record BM25 and ANN P50/P95. Project retrieval SLO remains P50 <= 300 ms, P95 <= 800 ms, P99 <= 1500 ms under the agreed workload.

P3 consistency/failure: run MySQL authoritative rebuild; verify snapshot count, SHA-256 checksum, source high-watermark; stop Elasticsearch/Milvus separately and verify Outbox RETRY -> recovery; force DEAD then replay; kill worker and verify stale-lock recovery; activate green then rollback old alias.

## Defects fixed during qualification
1. `.env.example` and `.env.production.example` still used Milvus port 6333. Corrected to 19530.
2. `KnowledgeIndexer._reset_schema_targets()` still called Qdrant-style `/collections/{name}`. Replaced with Milvus REST v2 collection list/drop.
3. Schema-release mapper parameter names still used `os/qd` abbreviations. Renamed to Elasticsearch/Milvus names.
4. Authoritative rebuild counted and streamed non-effective Card/Claim rows even though retrieval filters excluded them. Count and snapshot now agree on APPROVED/EFFECTIVE.
5. Snapshot projection did not carry an ACL field. Contract now carries `permissionTags`; current SQL emits public empty tags until authoritative ACL persistence is wired.
6. Old index-operations gate still inspected the compatibility `external.py`; it now validates the split vendor providers.

## Current blocker / release decision
This environment has no Docker and no Maven. Therefore live Elasticsearch/Milvus startup, Flyway execution against MySQL, Java package/Testcontainers, failure injection, real alias switching and 100k capacity qualification cannot be certified here.

Static/smoke status: QUALIFIED WITH LIVE-ENV BLOCKER.
Production release status: NOT YET APPROVED until P1-P3 live gates pass.

## Commands on a qualification host
```bash
cp .env.example .env
docker compose up -d mysql redis elasticsearch milvus neo4j ai-service backend
bash scripts/check-v11.sh
python scripts/release-qualification-live.py --documents 1000 --queries 100
python scripts/release-qualification-live.py --documents 10000 --queries 300
KB=1 TYPE=CARD CODE=K_CARD_001 STATUS=APPROVED ./scripts/fault-injection-indexing.sh
```

For 100k, use an isolated qualification namespace/KB and capture host CPU, RSS, disk, index size, ingestion throughput, query P50/P95/P99, error rate, Outbox lag and alias-switch interruption count.
