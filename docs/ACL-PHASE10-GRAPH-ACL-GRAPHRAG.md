# V1.1 ACL Phase 10 — Graph ACL Inheritance + Manifest Qualification + Multi-hop GraphRAG

## Scope
This round closes the Phase-9 gap between extracted graph candidates and the MySQL authoritative graph, adds machine verification of the dedicated graph manifest, and introduces generation-safe ACL-aware multi-hop traversal.

## Implemented
- Flyway V17 adds graph ACL provenance and ACL fields for Claim/Card→Entity links.
- KnowledgeExtractionService persists extracted Entity/Relation objects into MySQL with source-derived permission tags and provenance.
- Explicit Claim/Card entity links inherit the knowledge object's ACL contract.
- ACL rebuild qualification now passes the MySQL graph manifest hash/entity/relation counts to the AI qualification gate.
- Neo4j recomputes the physical generation graph manifest from EntityGen, RELATION_GEN and ABOUT_ENTITY_GEN rows; mismatches fail qualification.
- Added generation_multihop traversal: active-generation pinning, node+edge+evidence ACL pre-filtering, simple-path cycle control, max depth/limit and token budget.
- Added `/ai/graph/{knowledge_base_id}/{entity_code}/multihop`.

## Security invariants
1. MySQL remains authoritative.
2. Entity/Relation ACL derives from the source/extraction ACL context; no graph edge may broaden access.
3. Multi-hop traversal filters every node and relation before a path is returned.
4. Evidence binding is filtered under the same generation and ACL context.
5. Graph manifest mismatch blocks READY/activation.

## Executed validation
PASS:
- ACL Phase 10 contract: 10 checks.
- Code quality contract.
- Python py_compile for routes/indexer/graph provider.
- MyBatis XML parse.
- check-v11.sh shell syntax.
- Focused pytest with PYTHONPATH=ai-service: 9 passed in 0.19s.

Not executed / not claimed:
- Maven/Java compilation.
- Flyway V17 against live MySQL.
- Live Neo4j manifest recomputation.
- Live ES/Milvus/Neo4j full-stack qualification.
- Vite/vue-tsc production build.

## Remaining blocker
Entity Resolution can merge candidates from multiple sources. The current persistence path uses the current extraction source ACL. Production-grade merge semantics must compute a non-broadening ACL policy across all supporting sources/evidence, with conflict/authority rules. This should be Phase 11.

Production status remains NO-GO until live qualification and cross-source ACL merge semantics are proven.
