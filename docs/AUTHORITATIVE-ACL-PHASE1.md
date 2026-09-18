# Authoritative ACL Phase 1

## Goal
Persist ACL at the MySQL authority layer and propagate it into every retrieval view instead of rebuilding restricted knowledge as public.

## Contract
- Empty `permission_tags` means public.
- Non-empty tags mean the caller must possess at least one matching tag.
- Retrieval ACL is applied before Top-K.
- Request-body `permissionTags` is not an authoritative identity source.
- Backend resolves access tags only from a disabled-by-default trusted upstream header contract.

## Persistence
Flyway V10 adds JSON `permission_tags` to `t_source`, `t_knowledge_card`, and `t_claim`.
Source ACL is inherited by extracted Card/Claim and graph entities/relations.
Authoritative rebuild now emits persisted tags instead of `[]`.

## Retrieval
Elasticsearch, Milvus, local fallback and Neo4j use permission tags before Top-K.
Neo4j also filters graph neighbors and relationships to prevent side-channel leakage.

## Detail APIs
Knowledge object, Evidence, ContentUnit and Graph detail use the trusted ACL context and fail closed to public-only when no trusted identity tags are available.

## Identity boundary
`knowledge.acl.trusted-header-enabled=false` by default.
Only when deployment has an authenticated gateway/SSO that strips client-supplied headers and injects `X-Authenticated-Permission-Tags` should the trusted-header mode be enabled.

## Remaining Phase 2 blockers
This round does not claim enterprise identity integration is complete. Still required before Production GO:
- bind tags to the actual corporate user/role/department identity provider rather than a generic trusted-header contract;
- apply ACL to all management/list/report APIs, not only retrieval/detail paths;
- execute live MySQL + ES + Milvus + Neo4j leakage tests for anonymous/team-a/team-b;
- verify rebuild and blue/green preserve ACL under failures;
- audit permission changes and trigger reindex/graph updates.
