# Optimization Recovery — 2026-09-22

## Recovery base

This tree was rebuilt from the user-provided `ai-knowledge-os.zip` (Git HEAD `a253d64`) as the authoritative pre-optimization base.
The recovery starts from the request to improve frontend UI/role-friendly presentation and code architecture.

## Recovered changes

- UI shell: extracted `AppHeader.vue` and `AppSidebar.vue`; kept five first-level product areas; centralized role presentation in `role-experience.ts`.
- Authorization UX: `hasPermission([])` is deny-by-default; only null/undefined keeps compatibility permissive behavior.
- Frontend composable boundaries restored for role navigation, workspace context, query, ingestion, governance, overview, and ACL administration.
- Query service: split query understanding/routing/rewriting and ranking-presentation helpers from `query_engine.py`; restored query pipeline/trace/response module boundaries.
- Java quality debt: line-length gate restored without relaxing thresholds; public type/docstring debt fixed; `KnowledgeService` retained as a thin compatibility facade under the configured size gate.
- MyBatis physical decomposition: EvidenceMapper, GovernanceMapper, GraphMapper, IndexMapper, DocumentMapper.
- Mapper contract checker now validates every mapper namespace and ignores non-executable `<sql>` fragments.
- DocumentMapper includes Source, SourceVersion/File Metadata, Document, ContentUnit and Chunk persistence through P2.5B.
- Query-policy persistence keeps the active policy mirrored for backward-compatible readers while named profiles remain authoritative.

## Current mapper statement distribution

- AuthMapper: 26
- DashboardMapper: 4
- DocumentMapper: 18
- EvidenceMapper: 5
- ExternalApiMapper: 15
- GovernanceMapper: 26
- GraphMapper: 35
- IndexMapper: 38
- KnowledgeMapper: 157
- PlatformEventMapper: 5
- Total: 329

## Verified gates

- `python3 scripts/check-code-quality.py`: PASS
- `python3 scripts/check-mapper-contract.py`: PASS (10 namespaces / 329 statements)
- `python3 scripts/check-db-contract.py`: PASS (72 tables / 53 mapper tables / 12 trace columns)
- `python3 scripts/check-frontend-typescript.py`: PASS
- `PYTHONPATH=ai-service python3 -m pytest -q ai-service/tests/test_query_policy.py ai-service/tests/test_answer_style.py`: 16 passed

Maven compile was not claimed because Maven is unavailable in the execution environment.
