# ACL Phase 3 — Governance, Propagation, Audit

## Scope
This round adds an ACL governance workbench, a durable ACL change ledger, Transactional Outbox propagation, and access-audit recording for protected knowledge-detail reads.

## Data model
Flyway V12 adds `t_acl_change` and enriches `t_acl_access_audit` with trace/request metadata.

## Change flow
Admin UI → MySQL authoritative ACL → `t_acl_change` → same-transaction `ACL_CHANGED` Outbox → AI `/ai/index/object/acl` → local / Elasticsearch / Neo4j patch.

Milvus scalar partial-update is deliberately not claimed safe. The provider returns `requiresReindex=true`; Source ACL changes return `requiresAuthoritativeRebuild=true` because they cascade to chunks/evidence/derived knowledge. These remain production blockers until the worker automatically converts those signals into a verified authoritative rebuild/reindex workflow.

## Audit
Knowledge Detail writes ALLOW/DENY audit decisions with subject, KB, resource, action, effective tags, traceId and request URI. Phase 4 should move this to a centralized interceptor/policy enforcement layer so every protected API is covered consistently.

## UI
A new 权限治理 tab shows Subjects, Roles, ACL changes and access audits, and supports SOURCE/CARD/CLAIM permission-tag changes.
