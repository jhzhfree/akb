# ACL Phase 6 — Machine-Verifiable Leakage Qualification

## Goal
Remove the operator-controlled `passed=true` qualification switch. A rebuild job can become READY only from machine-produced evidence.

## Barrier
`VALIDATING -> machine qualifier -> PASSED/FAILED -> READY/FAILED -> activation`

The backend calls `/ai/index/schema/acl-qualify/{kb}` with the rebuild job's exact `schemaVersion`. The qualifier reads the physical green Elasticsearch/Milvus targets; it never switches aliases.

Personas: anonymous, team-a, team-b, admin. For each provider the result persists visible ids, allowed ids, leaked ids and missing ids into `qualification_json`. Any leaked id makes `qualified=false`, the job becomes FAILED, and the existing activation barrier refuses activation.

## Scope honesty
This phase machine-verifies the two blue/green retrieval engines managed by the current schema release: Elasticsearch and Milvus. Neo4j already has pre-query ACL filtering, but it is not generation-pinned to the ES/Milvus schema release, so this phase reports `graph=NOT_IN_BLUE_GREEN_SCHEMA_RELEASE` instead of falsely certifying graph generation consistency. Evidence/ContentUnit APIs remain protected by backend ACL enforcement but are not exercised by this green-index qualifier.

The expected ACL set is derived from green Elasticsearch metadata. Existing Phase 5 authoritative snapshot count/checksum/high-watermark validation remains a prerequisite and detects missing/extra snapshot rows. A future stronger gate should pass a signed/hashed authoritative ACL manifest from MySQL into qualification, eliminating Elasticsearch as the expected-set reference.

## Executed checks
- Phase 6 machine qualification contract: PASS
- Code quality: PASS
- Mapper contract: PASS (102 statements)
- DB contract: PASS (35 tables / 33 mapper tables / 12 trace columns)
- Python compile: PASS
- Focused retrieval/release pytest: 6 passed
- shell syntax: PASS

Not executed: Maven, vue-tsc/Vite build, live MySQL/Flyway, live ES/Milvus/Neo4j, Docker full stack, four-persona live E2E.

Production remains NO-GO.
