# V1.1 ACL Phase 13 — Immutable Evidence Binding + Citation-Safe GraphRAG

## Scope
Phase 13 closes the gap between evidence-scoped graph facts and citable evidence. A returned protected graph fact must have at least one evidence/source binding visible to the current persona.

## Changes
- Flyway V20 adds immutable entity/relation facet-to-evidence binding tables and graph evidence manifest.
- Authoritative graph rebuild now reads evidence bindings from MySQL and includes them in the green-generation rebuild request.
- Neo4j projects generation-scoped `EvidenceGen` and `SourceGen` plus `SUPPORTED_BY` and `DERIVED_FROM` edges.
- New citation-safe GraphRAG endpoint pre-filters RelationFacet, Evidence and Source ACL before returning a path.
- Each returned path must contain at least one visible citation; paths without evidence are dropped.
- Evidence manifest SHA-256 is generated from authoritative bindings for release qualification data.

## Security semantics
`Canonical Entity -> Facet -> Evidence -> Source` is intentionally separated. Canonical identity does not grant access to protected facts. A relation facet is usable for answer context only when facet ACL, evidence ACL and source ACL all permit the persona.

## Validation actually executed
- Phase 13 contract: 8/8 PASS.
- Code quality contract: PASS.
- Python py_compile: PASS.
- MyBatis XML parse: PASS.
- Focused pytest files requested in this environment were absent: `no tests ran`; not counted as PASS.

## Not executed / not claimed
- Maven / Java compilation.
- Live MySQL + Flyway V20.
- Live Neo4j evidence projection.
- Docker full stack.
- Live ES/Milvus/Neo4j release qualification.
- Vite/vue-tsc production build.

## Remaining blocker
The current authoritative binding query associates active evidence by source. Production-grade binding should be tightened to exact ContentUnit/Evidence IDs produced by extraction/adjudication rather than source-level association. The next phase should make Evidence IDs first-class outputs of Entity/Relation extraction and add machine-verifiable citation completeness/faithfulness gates.

Production remains NO-GO until live qualification evidence exists.
