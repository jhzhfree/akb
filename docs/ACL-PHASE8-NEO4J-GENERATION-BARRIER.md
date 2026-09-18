# ACL Phase 8 — Neo4j Generation + Three-Engine Barrier

## Goal
Bring Neo4j into the same governed generation and ACL qualification path as Elasticsearch and Milvus without claiming a distributed atomic commit across three independent engines.

## Governed graph generation
Each authoritative snapshot row is projected to a Neo4j `KnowledgeObject` node keyed by:

- `knowledgeBaseId`
- `generationId`
- `objectId`

The node persists `objectType`, `objectKey`, `contentHash`, `status`, `permissionTags`, title and content. Green rebuild deletes only the requested generation and never moves the active pointer.

`(:IndexGenerationPointer {knowledgeBaseId})` stores `activeGenerationId`. Query reads use this pointer before applying lifecycle and ACL filters.

## Three-engine rebuild
The authoritative MySQL page is now fanned out to:

1. Elasticsearch physical green index
2. Milvus physical green collection
3. Neo4j generation-scoped governed projection

Java `IndexSchemaService` requires all three counts to equal the authoritative snapshot count. A missing/unavailable graph therefore cannot silently pass production rebuild validation.

## Manifest qualification
Neo4j computes the same canonical SHA-256 manifest fields:

`objectKey + contentHash + status + sorted(permissionTags)`

Machine qualification compares MySQL's expected manifest with ES, Milvus and Neo4j, then runs Anonymous / Team-A / Team-B / Admin visibility checks against all three physical/generation targets.

## Activation and query safety
Activation still updates independent engines sequentially; it is not a distributed transaction. Neo4j activation is an atomic pointer update inside Neo4j. During the short cross-engine switch window, Hybrid Retrieval collects generation IDs from ES, Milvus and the Neo4j active pointer. If more than one generation is observed, or a generation is missing while another is present, query fails closed with `CROSS_ENGINE_GENERATION_MISMATCH`.

This closes silent mixed-generation reads, but it can cause temporary query unavailability during activation. A future availability round should introduce a request-level generation coordinator or dual-read grace protocol if zero-downtime activation is required.

## Important graph limitation
This phase generation-scopes the **governed retrieval projection** (`KnowledgeObject`) so ACL/manifest/generation safety can be verified. It does **not** yet rebuild the full semantic Entity/Relation graph from the authoritative MySQL snapshot, because the current authoritative snapshot contains ContentUnit/Card/Claim but not Entity/Relation rows. Existing legacy semantic graph writes remain separate.

Do not describe Phase 8 as full graph-lifecycle closure. The next graph round should add authoritative Entity/Relation snapshot rows, generation-scoped edges, relation ACL manifesting, and generation-safe neighborhood traversal.
