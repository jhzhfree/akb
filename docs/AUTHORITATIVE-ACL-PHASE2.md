# Authoritative ACL Phase 2 — Enterprise Identity / RBAC / ABAC

## Goal
Resolve permission tags from authenticated enterprise identity instead of trusting client-provided tags, and close list-page leakage for Source/Card/Claim.

## Identity model
V11 adds `t_acl_subject`, `t_acl_role`, `t_acl_subject_role`, `t_acl_role_tag`, `t_acl_subject_tag`, `t_acl_department_tag`, and `t_acl_access_audit`.

Effective tags are the union of direct subject tags, role tags, and department tags. `X-Authenticated-User-Id` is the identity key injected by a trusted SSO/gateway boundary. When DB identity resolution is enabled, caller-supplied permission-tag headers are not authoritative.

Defaults: `KNOWLEDGE_ACL_IDENTITY_DB_ENABLED=true`, `KNOWLEDGE_ACL_TRUSTED_HEADER_ENABLED=false`.

## List leakage closure
`/sources/{kb}`, `/cards/{kb}`, and `/claims/{kb}` now apply the same public-or-tag-intersection rule used by detail/query retrieval. Anonymous callers only see public rows.

## Leakage matrix
Automated persona contract covers Anonymous, Team-A, Team-B, and Admin. Public knowledge is visible to all; restricted knowledge requires at least one matching permission tag.

## Remaining blockers
The V11 audit table is schema-ready but access decision audit writes are not yet wired to every endpoint. Real SSO user synchronization, ACL administration APIs, ACL-change reindex propagation, live MySQL migration, and end-to-end ES/Milvus/Neo4j leakage qualification remain required before Production GO.
