# ACL Phase 12 — Evidence-Scoped Graph Projection

## Objective
Separate canonical entity identity from source/evidence-scoped facts so cross-source entity resolution never broadens ACL visibility.

## Authoritative model
V19 adds `t_entity_facet`, `t_relation_facet`, and `t_index_graph_facet_manifest`.
Extraction persists one stable facet per `canonical-code + ACL scope hash`; canonical Entity/Relation can remain `EVIDENCE_SCOPED` and fail closed while facets preserve source-specific names, descriptions, relations and permission tags.

## Neo4j generation projection
A green generation now contains:
- `EntityGen`: canonical identity.
- `EntityFacetGen -[:FACET_OF]-> EntityGen`: source-scoped descriptive fact.
- `RelationFacetGen -[:FACET_SOURCE]-> EntityGen` and `-[:FACET_TARGET]-> EntityGen`: source-scoped relationship fact.

Facet nodes carry `generationId`, `permissionTags`, `contentHash`, and status. Canonical identity is not treated as an ACL union.

## Manifest qualification
The authoritative graph snapshot now includes `ENTITY_FACET` and `RELATION_FACET`. Java computes both the full graph manifest and a facet-only manifest. Neo4j recomputes its physical manifest including facet projections, so missing/extra/modified protected facts change qualification output.

## Evidence-scoped GraphRAG
`GET /ai/graph/{knowledgeBaseId}/{entityCode}/facet-multihop` performs persona-aware traversal over visible RelationFacetGen facts. ACL is applied to the facet before it is returned. Response declares `FACET_PRE_FILTER_NON_BROADENING` and `CANONICAL_ENTITY_IDENTITY_ONLY`.

## Verification actually executed
- Phase 12 contract: 11 checks PASS.
- Code quality contract: PASS.
- Python py_compile: PASS.
- MyBatis XML parse: PASS.
- Focused ACL/graph/dual retrieval pytest: 5 passed in 0.12s.

## Not executed / not claimed
- Maven/Java compilation (Maven unavailable).
- Live MySQL + Flyway V19.
- Live Neo4j facet rebuild/manifest.
- Vite/vue-tsc production build.
- Docker full stack.
- Live three-engine activation qualification.

## Remaining boundary
Facet rows preserve source/document identifiers in MySQL, but Phase 12 does not yet project first-class EvidenceGen/SourceGen nodes into Neo4j. The next phase should add immutable evidence bindings and citation-safe path assembly, then verify that every returned facet has an accessible evidence chain.
